#define UNICODE
#define _UNICODE
#include <winsock2.h>
#include <windows.h>
#include <iostream>
#pragma comment(lib,"ws2_32.lib")
static bool blocked(unsigned long address,unsigned short port) {
 SOCKET s=socket(AF_INET,SOCK_STREAM,IPPROTO_TCP);if(s==INVALID_SOCKET)return true;
 u_long nonblocking=1;ioctlsocket(s,FIONBIO,&nonblocking);
 sockaddr_in addr{};addr.sin_family=AF_INET;addr.sin_port=htons(port);addr.sin_addr.s_addr=htonl(address);
 int result=connect(s,(sockaddr*)&addr,sizeof(addr)),error=WSAGetLastError();
 if(!result){closesocket(s);return false;}
 if(error==WSAEWOULDBLOCK){
  fd_set write,except;FD_ZERO(&write);FD_ZERO(&except);FD_SET(s,&write);FD_SET(s,&except);
  timeval wait{2,0};result=select(0,nullptr,&write,&except,&wait);
  if(result>0){int size=sizeof(error);getsockopt(s,SOL_SOCKET,SO_ERROR,(char*)&error,&size);}
  else error=WSAETIMEDOUT;
 }
 closesocket(s);return error!=0;
}
int wmain(int argc,wchar_t**argv) {
 if(argc!=3)return 2;
 HANDLE read=CreateFileW(argv[1],GENERIC_READ,FILE_SHARE_READ,nullptr,OPEN_EXISTING,0,nullptr);
 if(read!=INVALID_HANDLE_VALUE){CloseHandle(read);return 3;}
 if(GetLastError()!=ERROR_ACCESS_DENIED)return 4;
 HANDLE write=CreateFileW(argv[2],GENERIC_WRITE,0,nullptr,CREATE_NEW,0,nullptr);
 if(write!=INVALID_HANDLE_VALUE){CloseHandle(write);return 5;}
 if(GetLastError()!=ERROR_ACCESS_DENIED)return 6;
 WSADATA data;if(WSAStartup(MAKEWORD(2,2),&data))return 7;
 bool local=blocked(0x7f000001,9),publicNet=blocked(0x01010101,443);WSACleanup();
 if(!local||!publicNet)return 9;
 std::cout<<"APPCONTAINER_DENIALS_OK: user file read/write, live loopback and public TCP\n";
 return 0;
}
