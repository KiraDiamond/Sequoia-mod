// Test-only AppContainer probe. Never shipped in the mod.
#define UNICODE
#define _UNICODE
#include <winsock2.h>
#include <windows.h>
#include <iostream>
#include <string>
#pragma comment(lib,"ws2_32.lib")
int wmain(int argc,wchar_t**argv) {
 if(argc!=4)return 2;
 HANDLE file=CreateFileW(argv[1],GENERIC_READ,FILE_SHARE_READ,nullptr,OPEN_EXISTING,0,nullptr);
 if(file!=INVALID_HANDLE_VALUE){CloseHandle(file);return 3;}
 if(GetLastError()!=ERROR_ACCESS_DENIED)return 4;
 file=CreateFileW(argv[2],GENERIC_WRITE,0,nullptr,CREATE_NEW,0,nullptr);
 if(file!=INVALID_HANDLE_VALUE){CloseHandle(file);return 5;}
 if(GetLastError()!=ERROR_ACCESS_DENIED)return 6;
 WSADATA data;if(WSAStartup(MAKEWORD(2,2),&data))return 7;
 SOCKET socketHandle=socket(AF_INET,SOCK_STREAM,IPPROTO_TCP);
 int error=WSAGetLastError();
 if(socketHandle!=INVALID_SOCKET) {
  u_long nonblocking=1;ioctlsocket(socketHandle,FIONBIO,&nonblocking);
  sockaddr_in dest{};dest.sin_family=AF_INET;dest.sin_port=htons((u_short)_wtoi(argv[3]));
  dest.sin_addr.s_addr=htonl(0x7f000001);
  int result=connect(socketHandle,(sockaddr*)&dest,sizeof(dest));error=result==0?0:WSAGetLastError();
  if(error==WSAEWOULDBLOCK) {
   fd_set write,except;FD_ZERO(&write);FD_ZERO(&except);FD_SET(socketHandle,&write);FD_SET(socketHandle,&except);
   timeval wait{2,0};int ready=select(0,nullptr,&write,&except,&wait);
   if(ready>0){int length=sizeof(error);getsockopt(socketHandle,SOL_SOCKET,SO_ERROR,(char*)&error,&length);}
   else error=WSAETIMEDOUT;
  }
  closesocket(socketHandle);
 }
 WSACleanup();
 if(error!=WSAEACCES && error!=WSAETIMEDOUT)return 8;
 wchar_t system[MAX_PATH];if(!GetSystemDirectoryW(system,MAX_PATH))return 9;
 std::wstring command=L"\""+std::wstring(system)+L"\\cmd.exe\" /d /c exit";
 STARTUPINFOW start{};start.cb=sizeof(start);PROCESS_INFORMATION child{};
 if(CreateProcessW(nullptr,command.data(),nullptr,nullptr,FALSE,CREATE_SUSPENDED,nullptr,nullptr,&start,&child)) {
  TerminateProcess(child.hProcess,1);CloseHandle(child.hThread);CloseHandle(child.hProcess);return 10;
 }
 std::cout<<"APPCONTAINER_DENIALS_OK: user read write live loopback process\n";
 return 0;
}