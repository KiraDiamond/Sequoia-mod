// Start the decoder in a fresh AppContainer; the broker never parses media.
// Remove the profile when the child exits.
#define UNICODE
#define _UNICODE
#define _WIN32_WINNT 0x0A00
#include <windows.h>
#include <userenv.h>
#include <aclapi.h>
#include <sddl.h>
#include <objbase.h>
#include <filesystem>
#include <vector>
#include <string>
#include <stdexcept>
#include <iostream>
#pragma comment(lib,"userenv.lib")
#pragma comment(lib,"advapi32.lib")
#pragma comment(lib,"ole32.lib")
namespace fs=std::filesystem;
static void check(bool ok,const char* what) {
 if(!ok) throw std::runtime_error(std::string(what)+" ("+std::to_string(GetLastError())+")");
}
struct Handle {
 HANDLE h=nullptr;
 ~Handle(){if(h && h!=INVALID_HANDLE_VALUE)CloseHandle(h);}
 operator HANDLE()const{return h;}
};
static void safePath(const fs::path& path) {
 check(path.is_absolute(),"Absolute path required");
 for(auto p=path;!p.empty() && p!=p.root_path();p=p.parent_path()) {
  DWORD a=GetFileAttributesW(p.c_str());
  check(a!=INVALID_FILE_ATTRIBUTES && !(a&FILE_ATTRIBUTE_REPARSE_POINT),"Reparse path denied");
 }
}
static std::wstring quote(const std::wstring& s) {
 std::wstring out=L"\"";size_t slashes=0;
 for(wchar_t c:s) {
  if(c==L'\\'){++slashes;continue;}
  if(c==L'"')out.append(slashes*2+1,L'\\');
  else out.append(slashes,L'\\');
  slashes=0;out+=c;
 }
 out.append(slashes*2,L'\\');return out+L"\"";
}
struct Grants {
 PSID sid=nullptr;
 std::wstring profile;
 std::vector<fs::path> files;
 Handle mutex;
 Grants() {
  mutex.h=CreateMutexW(nullptr,FALSE,L"Local\\SequoiaImagesSandboxAclV1");
  check(mutex.h!=nullptr,"ACL mutex");
  GUID guid;check(SUCCEEDED(CoCreateGuid(&guid)),"Sandbox identity");
  wchar_t text[40];StringFromGUID2(guid,text,40);
  std::wstring name=L"SequoiaImages.";name+=text;profile=name;
  check(SUCCEEDED(CreateAppContainerProfile(name.c_str(),L"Sequoia Images decoder",L"Temporary media sandbox",nullptr,0,&sid)),"Sandbox SID");
 }
 void change(const fs::path& p,DWORD rights,ACCESS_MODE mode) {
  DWORD wait=WaitForSingleObject(mutex,10000);
  check(wait==WAIT_OBJECT_0 || wait==WAIT_ABANDONED,"ACL mutex wait");
  PSECURITY_DESCRIPTOR descriptor=nullptr;PACL oldAcl=nullptr,newAcl=nullptr;
  DWORD error=GetNamedSecurityInfoW(p.c_str(),SE_FILE_OBJECT,DACL_SECURITY_INFORMATION,
   nullptr,nullptr,&oldAcl,nullptr,&descriptor);
  if(error==ERROR_SUCCESS) {
   EXPLICIT_ACCESSW access{};access.grfAccessPermissions=rights;
   access.grfAccessMode=mode;access.grfInheritance=NO_INHERITANCE;
   access.Trustee.TrusteeForm=TRUSTEE_IS_SID;access.Trustee.TrusteeType=TRUSTEE_IS_UNKNOWN;
   access.Trustee.ptstrName=(LPWSTR)sid;
   error=SetEntriesInAclW(1,&access,oldAcl,&newAcl);
   if(error==ERROR_SUCCESS)error=SetNamedSecurityInfoW((LPWSTR)p.c_str(),SE_FILE_OBJECT,
    DACL_SECURITY_INFORMATION,nullptr,nullptr,newAcl,nullptr);
  }
  if(newAcl)LocalFree(newAcl);if(descriptor)LocalFree(descriptor);
  ReleaseMutex(mutex);SetLastError(error);check(error==ERROR_SUCCESS,"Sandbox file ACL");
 }
 void add(const fs::path& p,DWORD rights) {
  safePath(p);change(p,rights,GRANT_ACCESS);files.push_back(p);
 }
 void read(const fs::path& root) {
  add(root,FILE_GENERIC_READ|FILE_GENERIC_EXECUTE);
  if(fs::is_directory(root))for(auto& entry:fs::recursive_directory_iterator(root)) {
   check(!(GetFileAttributesW(entry.path().c_str())&FILE_ATTRIBUTE_REPARSE_POINT),"Runtime reparse denied");
   add(entry.path(),FILE_GENERIC_READ|FILE_GENERIC_EXECUTE);
  }
 }
 void scratch(const fs::path& root) {
  add(root,FILE_GENERIC_READ|FILE_GENERIC_WRITE|DELETE|FILE_DELETE_CHILD);
  // Child files need the same write permissions as this scratch directory.
  DWORD wait=WaitForSingleObject(mutex,10000);
  check(wait==WAIT_OBJECT_0 || wait==WAIT_ABANDONED,"Scratch ACL mutex");
  PSECURITY_DESCRIPTOR sd=nullptr;PACL acl=nullptr,next=nullptr;
  DWORD error=GetNamedSecurityInfoW(root.c_str(),SE_FILE_OBJECT,DACL_SECURITY_INFORMATION,nullptr,nullptr,&acl,nullptr,&sd);
  if(!error) {
   EXPLICIT_ACCESSW access{};access.grfAccessPermissions=FILE_GENERIC_READ|FILE_GENERIC_WRITE|DELETE;
   access.grfAccessMode=GRANT_ACCESS;access.grfInheritance=SUB_CONTAINERS_AND_OBJECTS_INHERIT;
   access.Trustee.TrusteeForm=TRUSTEE_IS_SID;access.Trustee.ptstrName=(LPWSTR)sid;
   error=SetEntriesInAclW(1,&access,acl,&next);
   if(!error)error=SetNamedSecurityInfoW((LPWSTR)root.c_str(),SE_FILE_OBJECT,DACL_SECURITY_INFORMATION,nullptr,nullptr,next,nullptr);
  }
  if(next)LocalFree(next);if(sd)LocalFree(sd);ReleaseMutex(mutex);
  SetLastError(error);check(error==ERROR_SUCCESS,"Scratch inheritance");
  for(auto& entry:fs::recursive_directory_iterator(root))add(entry.path(),FILE_GENERIC_READ|FILE_GENERIC_WRITE|DELETE);
 }
 bool cleanup() noexcept {
  bool ok=true;
  for(auto i=files.rbegin();i!=files.rend();++i)try {change(*i,0,REVOKE_ACCESS);}catch(...){ok=false;}
  files.clear();return ok;
 }
 ~Grants(){cleanup();if(sid){DeleteAppContainerProfile(profile.c_str());FreeSid(sid);}}
};
int wmain(int argc,wchar_t** argv) {
 try {
  // seconds, scratch, number of read paths, read paths..., executable, argv...
  check(argc>=6,"Sandbox arguments");
  int seconds=std::stoi(argv[1]),count=std::stoi(argv[3]);
  check(seconds>=1 && seconds<=120 && count>=1 && count<=4 && argc>4+count,"Sandbox limits");
  fs::path scratch=fs::absolute(argv[2]);safePath(scratch);
  fs::path exe=fs::absolute(argv[4+count]);safePath(exe);
  Grants grants;
  for(int i=0;i<count;i++)grants.read(fs::absolute(argv[4+i]));
  // Input and output use inherited pipes, so the child needs no scratch-directory grant.
  SECURITY_CAPABILITIES security{};security.AppContainerSid=grants.sid; // No network or other capabilities.
  Handle job;job.h=CreateJobObjectW(nullptr,nullptr);check(job.h!=nullptr,"Sandbox job");
  JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits{};
  limits.BasicLimitInformation.LimitFlags=JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE|JOB_OBJECT_LIMIT_ACTIVE_PROCESS|JOB_OBJECT_LIMIT_PROCESS_MEMORY;
  limits.BasicLimitInformation.ActiveProcessLimit=1;limits.ProcessMemoryLimit=512ULL*1024*1024;
  check(SetInformationJobObject(job,JobObjectExtendedLimitInformation,&limits,sizeof(limits)),"Sandbox job limits");
  JOBOBJECT_BASIC_UI_RESTRICTIONS ui{};ui.UIRestrictionsClass=0xff;
  check(SetInformationJobObject(job,JobObjectBasicUIRestrictions,&ui,sizeof(ui)),"Sandbox UI restrictions");
  Handle input,output,error;
  check(DuplicateHandle(GetCurrentProcess(),GetStdHandle(STD_INPUT_HANDLE),GetCurrentProcess(),&input.h,0,TRUE,DUPLICATE_SAME_ACCESS),"Input pipe");
  check(DuplicateHandle(GetCurrentProcess(),GetStdHandle(STD_OUTPUT_HANDLE),GetCurrentProcess(),&output.h,0,TRUE,DUPLICATE_SAME_ACCESS),"Output pipe");
  check(DuplicateHandle(GetCurrentProcess(),GetStdHandle(STD_ERROR_HANDLE),GetCurrentProcess(),&error.h,0,TRUE,DUPLICATE_SAME_ACCESS),"Error pipe");
  HANDLE inherited[]={input.h,output.h,error.h},jobs[]={job.h};
  SIZE_T size=0;InitializeProcThreadAttributeList(nullptr,3,0,&size);
  std::vector<BYTE> storage(size);
  auto attributes=(LPPROC_THREAD_ATTRIBUTE_LIST)storage.data();
  check(InitializeProcThreadAttributeList(attributes,3,0,&size),"Process attributes");
  struct AttributeCleanup {LPPROC_THREAD_ATTRIBUTE_LIST p;~AttributeCleanup(){DeleteProcThreadAttributeList(p);}} ac{attributes};
  check(UpdateProcThreadAttribute(attributes,0,PROC_THREAD_ATTRIBUTE_SECURITY_CAPABILITIES,
   &security,sizeof(security),nullptr,nullptr),"AppContainer attributes");
  check(UpdateProcThreadAttribute(attributes,0,PROC_THREAD_ATTRIBUTE_HANDLE_LIST,inherited,sizeof(inherited),nullptr,nullptr),"Pipe handles");
  // Assign at creation so cancelling the broker cannot orphan a suspended child.
  check(UpdateProcThreadAttribute(attributes,0,PROC_THREAD_ATTRIBUTE_JOB_LIST,jobs,sizeof(jobs),nullptr,nullptr),"Sandbox job attribute");
  STARTUPINFOEXW startup{};startup.StartupInfo.cb=sizeof(startup);startup.lpAttributeList=attributes;
  startup.StartupInfo.dwFlags=STARTF_USESTDHANDLES;startup.StartupInfo.hStdInput=input;startup.StartupInfo.hStdOutput=output;startup.StartupInfo.hStdError=error;
  std::wstring command;
  for(int i=4+count;i<argc;i++){if(!command.empty())command+=L" ";command+=quote(argv[i]);}
  // Build a fresh environment to keep launcher secrets and injection options out of the child.
  wchar_t windows[MAX_PATH];check(GetWindowsDirectoryW(windows,MAX_PATH)>0,"Windows directory");
  wchar_t local[32768];check(GetEnvironmentVariableW(L"LOCALAPPDATA",local,32768)>0,"Local app data");
  std::vector<std::wstring> vars={L"LOCALAPPDATA="+std::wstring(local),L"SystemRoot="+std::wstring(windows),L"TEMP="+scratch.wstring(),
   L"TMP="+scratch.wstring(),L"windir="+std::wstring(windows)};
  std::wstring environment;for(auto& v:vars){environment+=v;environment+=L'\0';}environment+=L'\0';
  PROCESS_INFORMATION pi{};
  check(CreateProcessW(exe.c_str(),command.data(),nullptr,nullptr,TRUE,
   EXTENDED_STARTUPINFO_PRESENT|CREATE_UNICODE_ENVIRONMENT|CREATE_SUSPENDED|CREATE_NO_WINDOW,
   environment.data(),scratch.c_str(),&startup.StartupInfo,&pi),"Create AppContainer process");
  Handle process,thread;process.h=pi.hProcess;thread.h=pi.hThread;
  BOOL assigned=FALSE;
  if(!IsProcessInJob(process,job,&assigned) || !assigned){TerminateProcess(process,1);check(false,"Verify sandbox job");}
  Handle token;check(OpenProcessToken(process,TOKEN_QUERY,&token.h),"Sandbox token");
  DWORD appContainer=0,returned=0;
  check(GetTokenInformation(token,TokenIsAppContainer,&appContainer,sizeof(appContainer),&returned) && appContainer,"Verify AppContainer token");
  // Check the actual token before allowing the decoder to run.
  GetTokenInformation(token,TokenCapabilities,nullptr,0,&returned);
  std::vector<BYTE> caps(returned);
  check(GetTokenInformation(token,TokenCapabilities,caps.data(),returned,&returned) &&
   ((TOKEN_GROUPS*)caps.data())->GroupCount==0,"Verify zero sandbox capabilities");
  check(ResumeThread(thread)!=DWORD(-1),"Resume sandbox");
  DWORD wait=WaitForSingleObject(process,seconds*1000);
  if(wait!=WAIT_OBJECT_0){TerminateJobObject(job,1);WaitForSingleObject(process,3000);throw std::runtime_error("Sandbox time limit");}
  DWORD code=1;check(GetExitCodeProcess(process,&code),"Sandbox result");
  check(grants.cleanup(),"Sandbox ACL cleanup");
  return static_cast<int>(code);
 } catch(const std::exception& e) {
  std::cerr<<"Sandbox blocked: "<<e.what()<<"\n";return 1;
 }
}
