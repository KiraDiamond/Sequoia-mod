// Start the decoder in a fresh AppContainer; the broker never parses media.
// The broker owns the AppContainer profile and the file grants it creates. It removes them when
// the decoder exits, when its time limit expires, or when the caller asks it to cancel by
// creating <scratch>\cancel. A lease file records that state before it exists, so if the broker
// itself is killed, the next broker revokes the grants and deletes the profile.
#define UNICODE
#define _UNICODE
#define _WIN32_WINNT 0x0A00
#include <windows.h>
#include <userenv.h>
#include <aclapi.h>
#include <sddl.h>
#include <objbase.h>
#include <algorithm>
#include <cstdint>
#include <filesystem>
#include <vector>
#include <string>
#include <stdexcept>
#include <iostream>
#include <utility>
#pragma comment(lib,"userenv.lib")
#pragma comment(lib,"advapi32.lib")
#pragma comment(lib,"ole32.lib")
namespace fs=std::filesystem;
static constexpr int CANCELLED=3;
// Cleanup runs after the decoder ends, and Java reads the result only once the broker exits,
// so it waits briefly for the mutex and otherwise leaves the state to the lease.
static constexpr DWORD MUTEX_WAIT_MS=10000,CLEANUP_WAIT_MS=2000,CHILD_EXIT_MS=3000;
static constexpr size_t MAX_SWEPT_LEASES=16,MAX_LEASE_FILES=256;
static constexpr DWORD MAX_LEASE_BYTES=64*1024;
static void check(bool ok,const char* what) {
 if(!ok) throw std::runtime_error(std::string(what)+" ("+std::to_string(GetLastError())+")");
}
class Handle {
 HANDLE h=nullptr;
public:
 Handle()=default;
 explicit Handle(HANDLE value) noexcept:h(value){}
 Handle(const Handle&)=delete;
 Handle& operator=(const Handle&)=delete;
 Handle(Handle&& other) noexcept:h(std::exchange(other.h,nullptr)){}
 Handle& operator=(Handle&& other) noexcept {
  if(this!=&other){reset();h=std::exchange(other.h,nullptr);}
  return *this;
 }
 ~Handle(){reset();}
 void reset() noexcept {if(valid())CloseHandle(h);h=nullptr;}
 bool valid() const noexcept {return h && h!=INVALID_HANDLE_VALUE;}
 HANDLE get() const noexcept {return h;}
 HANDLE* out() noexcept {reset();return &h;}
 operator HANDLE() const noexcept {return h;}
};
// A directory change notification, which has its own close function.
class ChangeNotification {
 HANDLE h;
public:
 explicit ChangeNotification(const fs::path& directory)
  :h(FindFirstChangeNotificationW(directory.c_str(),FALSE,FILE_NOTIFY_CHANGE_FILE_NAME)) {
  check(h!=INVALID_HANDLE_VALUE,"Cancel watch");
 }
 ChangeNotification(const ChangeNotification&)=delete;
 ChangeNotification& operator=(const ChangeNotification&)=delete;
 ~ChangeNotification(){FindCloseChangeNotification(h);}
 HANDLE get() const noexcept {return h;}
};
// Serializes ACL edits and lease sweeps between concurrent brokers.
class MutexLock {
 HANDLE mutex;
public:
 explicit MutexLock(HANDLE m,DWORD waitMs=MUTEX_WAIT_MS):mutex(m) {
  DWORD wait=WaitForSingleObject(mutex,waitMs);
  check(wait==WAIT_OBJECT_0 || wait==WAIT_ABANDONED,"ACL mutex wait");
 }
 MutexLock(const MutexLock&)=delete;
 MutexLock& operator=(const MutexLock&)=delete;
 ~MutexLock(){ReleaseMutex(mutex);}
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
// Grants or revokes one SID's explicit access entry on a file. Call with the ACL mutex held.
static DWORD setAccess(const fs::path& p,PSID sid,DWORD rights,ACCESS_MODE mode) {
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
 if(newAcl)LocalFree(newAcl);
 if(descriptor)LocalFree(descriptor);
 return error;
}
static std::uint64_t ticks(FILETIME time) {
 return (std::uint64_t(time.dwHighDateTime)<<32)|time.dwLowDateTime;
}
static std::uint64_t creationTime(HANDLE process) {
 FILETIME created,exited,kernel,user;
 if(!GetProcessTimes(process,&created,&exited,&kernel,&user))return 0;
 return ticks(created);
}
static bool profileGone(HRESULT result) {
 return SUCCEEDED(result) || result==HRESULT_FROM_WIN32(ERROR_NOT_FOUND)
  || result==HRESULT_FROM_WIN32(ERROR_FILE_NOT_FOUND);
}
// Durable record of AppContainer state: owner PID and start time, profile name, then every
// granted path, one UTF-16 line each. Each line is flushed before the state it describes exists.
class Lease {
 fs::path file;
 Handle stream;
 size_t size=0;
 void line(const std::wstring& text) {
  std::wstring record=text+L"\n";DWORD written=0;
  DWORD bytes=DWORD(record.size()*sizeof(wchar_t));
  // Never write more than a sweep reads back.
  check(size+bytes<=MAX_LEASE_BYTES,"Lease size");size+=bytes;
  check(WriteFile(stream,record.data(),bytes,&written,nullptr) && written==bytes,"Lease write");
  check(FlushFileBuffers(stream),"Lease flush");
 }
public:
 Lease(const fs::path& directory,const std::wstring& profile):file(directory/(profile+L".lease")) {
  stream=Handle(CreateFileW(file.c_str(),GENERIC_WRITE,0,nullptr,CREATE_NEW,FILE_ATTRIBUTE_NORMAL,nullptr));
  check(stream.valid(),"Lease create");
  line(std::to_wstring(GetCurrentProcessId()));
  line(std::to_wstring(creationTime(GetCurrentProcess())));
  line(profile);
 }
 Lease(const Lease&)=delete;
 Lease& operator=(const Lease&)=delete;
 void record(const fs::path& granted){line(granted.wstring());}
 // Forgets the state once it is gone; a remaining file means later sweeps retry.
 void release() noexcept {stream.reset();DeleteFileW(file.c_str());}
};
static fs::path leaseDirectory() {
 wchar_t local[32768];
 check(GetEnvironmentVariableW(L"LOCALAPPDATA",local,32768)>0,"Local app data");
 fs::path directory=fs::path(local)/L"SequoiaImages"/L"leases";
 std::error_code error;fs::create_directories(directory,error);
 check(!error,"Lease directory");
 return directory;
}
static std::vector<std::wstring> readLease(const fs::path& file) {
 Handle stream(CreateFileW(file.c_str(),GENERIC_READ,FILE_SHARE_READ,nullptr,OPEN_EXISTING,FILE_ATTRIBUTE_NORMAL,nullptr));
 if(!stream.valid())return {};
 LARGE_INTEGER size{};
 if(!GetFileSizeEx(stream,&size) || size.QuadPart>MAX_LEASE_BYTES)return {};
 // A torn final record is dropped below; its state was never created.
 DWORD bytes=DWORD(size.QuadPart-size.QuadPart%sizeof(wchar_t));
 std::wstring text(bytes/sizeof(wchar_t),L'\0');DWORD read=0;
 if(!ReadFile(stream,text.data(),bytes,&read,nullptr) || read!=bytes)return {};
 std::vector<std::wstring> lines;size_t start=0,end;
 while((end=text.find(L'\n',start))!=std::wstring::npos){lines.push_back(text.substr(start,end-start));start=end+1;}
 return lines;
}
// A broker runs as this user, so it can always be opened for query. Any process that cannot
// be is not the lease's owner, even if it reuses the owner's PID.
static bool ownerAlive(const std::wstring& pid,const std::wstring& created) {
 DWORD id;std::uint64_t time;
 try{id=std::stoul(pid);time=std::stoull(created);}catch(...){return false;}
 Handle process(OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION,FALSE,id));
 DWORD code=0;
 return process.valid() && GetExitCodeProcess(process,&code) && code==STILL_ACTIVE
  && creationTime(process)==time;
}
static bool olderThanMinutes(const fs::path& file,unsigned minutes) {
 WIN32_FILE_ATTRIBUTE_DATA data;
 if(!GetFileAttributesExW(file.c_str(),GetFileExInfoStandard,&data))return false;
 FILETIME now;GetSystemTimeAsFileTime(&now);
 return ticks(now)-ticks(data.ftLastWriteTime)>std::uint64_t(minutes)*60*10000000;
}
// Removes profiles and grants left by brokers that were killed before cleaning up. Leases
// are tried oldest first and a failed attempt moves to the back, so a lease that cannot be
// cleaned yet does not block the others.
static void sweepStaleLeases(const fs::path& directory,HANDLE mutex) {
 MutexLock lock(mutex);
 struct Stale {fs::file_time_type written;fs::path file;std::vector<std::wstring> lines;};
 std::vector<Stale> stale;size_t scanned=0;
 for(auto& entry:fs::directory_iterator(directory)) {
  if(++scanned>MAX_LEASE_FILES)break;
  if(entry.path().extension()!=L".lease")continue;
  auto lines=readLease(entry.path());
  if(lines.size()<3) {
   // A broker killed while writing its header never created a profile.
   bool orphan=lines.size()==2?!ownerAlive(lines[0],lines[1]):olderThanMinutes(entry.path(),10);
   if(orphan)DeleteFileW(entry.path().c_str());
   continue;
  }
  // Only leases this broker could have written name a matching Sequoia profile.
  if(lines[2].rfind(L"SequoiaImages.",0)!=0 || lines[2]+L".lease"!=entry.path().filename().wstring()) {
   DeleteFileW(entry.path().c_str());
   continue;
  }
  if(!ownerAlive(lines[0],lines[1]))stale.push_back({entry.last_write_time(),entry.path(),std::move(lines)});
 }
 std::sort(stale.begin(),stale.end(),[](const Stale& a,const Stale& b){return a.written<b.written;});
 if(stale.size()>MAX_SWEPT_LEASES)stale.resize(MAX_SWEPT_LEASES);
 for(auto& lease:stale) {
  const std::wstring& profile=lease.lines[2];
  bool clean=true;PSID sid=nullptr;
  if(SUCCEEDED(DeriveAppContainerSidFromAppContainerName(profile.c_str(),&sid))) {
   for(size_t i=3;i<lease.lines.size();++i) {
    fs::path granted(lease.lines[i]);
    if(GetFileAttributesW(granted.c_str())==INVALID_FILE_ATTRIBUTES)continue;
    if(setAccess(granted,sid,0,REVOKE_ACCESS)!=ERROR_SUCCESS)clean=false;
   }
   FreeSid(sid);
  } else clean=false;
  if(clean && profileGone(DeleteAppContainerProfile(profile.c_str()))) DeleteFileW(lease.file.c_str());
  else {std::error_code ignored;fs::last_write_time(lease.file,fs::file_time_type::clock::now(),ignored);}
 }
}
// One AppContainer profile and its file grants, owned by this scope.
class Grants {
 HANDLE mutex;
 std::wstring profileName;
 Lease lease;
 PSID sid=nullptr;
 std::vector<fs::path> files;
 bool attempted=false,released=false;
 static std::wstring freshName() {
  GUID guid;check(SUCCEEDED(CoCreateGuid(&guid)),"Sandbox identity");
  wchar_t text[40];StringFromGUID2(guid,text,40);
  return std::wstring(L"SequoiaImages.")+text;
 }
public:
 Grants(HANDLE m,const fs::path& leases):mutex(m),profileName(freshName()),lease(leases,profileName) {
  HRESULT created=CreateAppContainerProfile(profileName.c_str(),L"Sequoia Images decoder",L"Temporary media sandbox",nullptr,0,&sid);
  if(FAILED(created)) {
   lease.release();
   throw std::runtime_error("Sandbox SID ("+std::to_string(created)+")");
  }
 }
 Grants(const Grants&)=delete;
 Grants& operator=(const Grants&)=delete;
 PSID appContainer() const noexcept {return sid;}
 void add(const fs::path& p,DWORD rights) {
  safePath(p);
  MutexLock lock(mutex);
  lease.record(p);
  DWORD error=setAccess(p,sid,rights,GRANT_ACCESS);
  SetLastError(error);check(error==ERROR_SUCCESS,"Sandbox file ACL");
  files.push_back(p);
 }
 // Revokes every grant and deletes the profile; the lease goes only when both succeeded.
 // One attempt: whatever remains is left to the next broker's sweep.
 bool release() noexcept {
  if(attempted)return released;
  attempted=true;
  bool ok=true;
  try {
   MutexLock lock(mutex,CLEANUP_WAIT_MS);
   for(auto i=files.rbegin();i!=files.rend();++i)
    if(setAccess(*i,sid,0,REVOKE_ACCESS)!=ERROR_SUCCESS)ok=false;
  } catch(...) {ok=false;}
  if(ok)files.clear();
  if(sid && ok && profileGone(DeleteAppContainerProfile(profileName.c_str()))) {
   FreeSid(sid);sid=nullptr;lease.release();released=true;
  }
  return released;
 }
 ~Grants(){release();if(sid)FreeSid(sid);}
};
static bool cancelRequested(const fs::path& scratch) {
 return GetFileAttributesW((scratch/L"cancel").c_str())!=INVALID_FILE_ATTRIBUTES;
}
enum class Outcome {Exited,Cancelled,TimedOut};
static Outcome waitForChild(HANDLE process,const ChangeNotification& watch,const fs::path& scratch,DWORD limit) {
 ULONGLONG deadline=GetTickCount64()+limit;
 for(;;) {
  ULONGLONG now=GetTickCount64();
  if(now>=deadline)return Outcome::TimedOut;
  HANDLE waits[]={process,watch.get()};
  DWORD wait=WaitForMultipleObjects(2,waits,FALSE,DWORD(deadline-now));
  if(wait==WAIT_OBJECT_0)return Outcome::Exited;
  if(wait==WAIT_TIMEOUT)return Outcome::TimedOut;
  check(wait==WAIT_OBJECT_0+1,"Sandbox wait");
  if(cancelRequested(scratch))return Outcome::Cancelled;
  check(FindNextChangeNotification(watch.get()),"Cancel watch");
 }
}
int wmain(int argc,wchar_t** argv) {
 try {
  // seconds, scratch, number of read paths, read paths..., executable, argv...
  check(argc>=6,"Sandbox arguments");
  int seconds=std::stoi(argv[1]),count=std::stoi(argv[3]);
  check(seconds>=1 && seconds<=120 && count>=1 && count<=4 && argc>4+count,"Sandbox limits");
  fs::path scratch=fs::absolute(argv[2]);safePath(scratch);
  fs::path exe=fs::absolute(argv[4+count]);safePath(exe);
  Handle mutex(CreateMutexW(nullptr,FALSE,L"Local\\SequoiaImagesSandboxAclV1"));
  check(mutex.valid(),"ACL mutex");
  fs::path leases=leaseDirectory();
  try {sweepStaleLeases(leases,mutex);}
  catch(const std::exception& e) {std::cerr<<"Sandbox sweep incomplete: "<<e.what()<<"\n";}
  // Watch before checking, so a cancel created in between is still seen.
  ChangeNotification watch(scratch);
  if(cancelRequested(scratch))return CANCELLED;
  Grants grants(mutex,leases);
  for(int i=0;i<count;i++)grants.add(fs::absolute(argv[4+i]),FILE_GENERIC_READ|FILE_GENERIC_EXECUTE);
  // Input and output use inherited pipes, so the child needs no scratch-directory grant.
  SECURITY_CAPABILITIES security{};security.AppContainerSid=grants.appContainer(); // No network or other capabilities.
  Handle job(CreateJobObjectW(nullptr,nullptr));check(job.valid(),"Sandbox job");
  JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits{};
  limits.BasicLimitInformation.LimitFlags=JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE|JOB_OBJECT_LIMIT_ACTIVE_PROCESS|JOB_OBJECT_LIMIT_PROCESS_MEMORY;
  limits.BasicLimitInformation.ActiveProcessLimit=1;limits.ProcessMemoryLimit=512ULL*1024*1024;
  check(SetInformationJobObject(job,JobObjectExtendedLimitInformation,&limits,sizeof(limits)),"Sandbox job limits");
  JOBOBJECT_BASIC_UI_RESTRICTIONS ui{};ui.UIRestrictionsClass=0xff;
  check(SetInformationJobObject(job,JobObjectBasicUIRestrictions,&ui,sizeof(ui)),"Sandbox UI restrictions");
  Handle input,output,error;
  check(DuplicateHandle(GetCurrentProcess(),GetStdHandle(STD_INPUT_HANDLE),GetCurrentProcess(),input.out(),0,TRUE,DUPLICATE_SAME_ACCESS),"Input pipe");
  check(DuplicateHandle(GetCurrentProcess(),GetStdHandle(STD_OUTPUT_HANDLE),GetCurrentProcess(),output.out(),0,TRUE,DUPLICATE_SAME_ACCESS),"Output pipe");
  check(DuplicateHandle(GetCurrentProcess(),GetStdHandle(STD_ERROR_HANDLE),GetCurrentProcess(),error.out(),0,TRUE,DUPLICATE_SAME_ACCESS),"Error pipe");
  HANDLE inherited[]={input.get(),output.get(),error.get()},jobs[]={job.get()};
  SIZE_T size=0;InitializeProcThreadAttributeList(nullptr,3,0,&size);
  std::vector<BYTE> storage(size);
  auto attributes=(LPPROC_THREAD_ATTRIBUTE_LIST)storage.data();
  check(InitializeProcThreadAttributeList(attributes,3,0,&size),"Process attributes");
  struct AttributeCleanup {
   LPPROC_THREAD_ATTRIBUTE_LIST p;
   explicit AttributeCleanup(LPPROC_THREAD_ATTRIBUTE_LIST list):p(list){}
   AttributeCleanup(const AttributeCleanup&)=delete;
   AttributeCleanup& operator=(const AttributeCleanup&)=delete;
   ~AttributeCleanup(){DeleteProcThreadAttributeList(p);}
  } attributeCleanup(attributes);
  check(UpdateProcThreadAttribute(attributes,0,PROC_THREAD_ATTRIBUTE_SECURITY_CAPABILITIES,
   &security,sizeof(security),nullptr,nullptr),"AppContainer attributes");
  check(UpdateProcThreadAttribute(attributes,0,PROC_THREAD_ATTRIBUTE_HANDLE_LIST,inherited,sizeof(inherited),nullptr,nullptr),"Pipe handles");
  // Assign at creation so cancelling the broker cannot orphan a suspended child.
  check(UpdateProcThreadAttribute(attributes,0,PROC_THREAD_ATTRIBUTE_JOB_LIST,jobs,sizeof(jobs),nullptr,nullptr),"Sandbox job attribute");
  STARTUPINFOEXW startup{};startup.StartupInfo.cb=sizeof(startup);startup.lpAttributeList=attributes;
  startup.StartupInfo.dwFlags=STARTF_USESTDHANDLES;startup.StartupInfo.hStdInput=input;startup.StartupInfo.hStdOutput=output;startup.StartupInfo.hStdError=error;
  std::wstring command;
  for(int i=4+count;i<argc;i++){if(!command.empty())command+=L" ";command+=quote(argv[i]);}
  // A fresh environment keeps launcher secrets and injection options out of the child. Creating
  // an AppContainer process fails with ERROR_ENVVAR_NOT_FOUND without LOCALAPPDATA.
  wchar_t windows[MAX_PATH];check(GetWindowsDirectoryW(windows,MAX_PATH)>0,"Windows directory");
  wchar_t local[32768];check(GetEnvironmentVariableW(L"LOCALAPPDATA",local,32768)>0,"Local app data");
  std::vector<std::wstring> vars={L"LOCALAPPDATA="+std::wstring(local),L"SystemRoot="+std::wstring(windows),L"TEMP="+scratch.wstring(),
   L"TMP="+scratch.wstring(),L"windir="+std::wstring(windows)};
  std::wstring environment;for(auto& v:vars){environment+=v;environment+=L'\0';}environment+=L'\0';
  PROCESS_INFORMATION pi{};
  check(CreateProcessW(exe.c_str(),command.data(),nullptr,nullptr,TRUE,
   EXTENDED_STARTUPINFO_PRESENT|CREATE_UNICODE_ENVIRONMENT|CREATE_SUSPENDED|CREATE_NO_WINDOW,
   environment.data(),scratch.c_str(),&startup.StartupInfo,&pi),"Create AppContainer process");
  Handle process(pi.hProcess),thread(pi.hThread);
#ifdef SEQIMAGES_TEST_PAUSE
  // Test build: stop once the suspended child exists, so tests can cancel or kill the broker here.
  std::cout<<"SUSPENDED_CHILD "<<pi.dwProcessId<<"\n"<<std::flush;Sleep(INFINITE);
#endif
  BOOL assigned=FALSE;
  if(!IsProcessInJob(process,job,&assigned) || !assigned){TerminateProcess(process,1);check(false,"Verify sandbox job");}
  Handle token;check(OpenProcessToken(process,TOKEN_QUERY,token.out()),"Sandbox token");
  DWORD appContainer=0,returned=0;
  check(GetTokenInformation(token,TokenIsAppContainer,&appContainer,sizeof(appContainer),&returned) && appContainer,"Verify AppContainer token");
  // Check the actual token before allowing the decoder to run.
  GetTokenInformation(token,TokenCapabilities,nullptr,0,&returned);
  std::vector<BYTE> caps(returned);
  check(GetTokenInformation(token,TokenCapabilities,caps.data(),returned,&returned) &&
   ((TOKEN_GROUPS*)caps.data())->GroupCount==0,"Verify zero sandbox capabilities");
  Outcome outcome=cancelRequested(scratch)?Outcome::Cancelled:Outcome::Exited;
  if(outcome==Outcome::Exited) {
   check(ResumeThread(thread)!=DWORD(-1),"Resume sandbox");
   outcome=waitForChild(process,watch,scratch,DWORD(seconds)*1000);
  }
  if(outcome!=Outcome::Exited) {
   TerminateJobObject(job,1);WaitForSingleObject(process,CHILD_EXIT_MS);
   if(!grants.release())std::cerr<<"Sandbox cleanup deferred to the next broker\n";
   if(outcome==Outcome::Cancelled)return CANCELLED;
   throw std::runtime_error("Sandbox time limit");
  }
  DWORD code=1;check(GetExitCodeProcess(process,&code),"Sandbox result");
  // The decode already finished; a lease keeps any state that cannot be removed now.
  if(!grants.release())std::cerr<<"Sandbox cleanup deferred to the next broker\n";
  return static_cast<int>(code);
 } catch(const std::exception& e) {
  std::cerr<<"Sandbox blocked: "<<e.what()<<"\n";return 1;
 }
}
