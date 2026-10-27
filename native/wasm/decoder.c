#include <stdint.h>
#include <string.h>
#include <errno.h>
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/intreadwrite.h>
#include <libswscale/swscale.h>
#define MAX_INPUT (8*1024*1024)
#define MAX_OUTPUT 48001616u
static uint8_t *output;
static uint32_t outlen, count, width, height;
static int still_image;
static uint64_t source_work;
static int64_t previous_pts=AV_NOPTS_VALUE;
static uint32_t last_offset;
typedef struct {const uint8_t *bytes;int length,position;} Input;
__attribute__((export_name("allocate_input"))) uint8_t *allocate_input(int n) {
 if(n<=0 || n>MAX_INPUT)return NULL;
 return av_mallocz((size_t)n+AV_INPUT_BUFFER_PADDING_SIZE);
}
__attribute__((export_name("output_ptr"))) uint8_t *output_ptr(void){return output;}
__attribute__((export_name("output_len"))) uint32_t output_len(void){return outlen;}
static int read_bytes(void *opaque,uint8_t *buf,int n) {
 Input *i=opaque;if(i->position>=i->length)return AVERROR_EOF;
 if(n>i->length-i->position)n=i->length-i->position;
 memcpy(buf,i->bytes+i->position,n);i->position+=n;return n;
}
static int64_t seek_bytes(void *opaque,int64_t pos,int whence) {
 Input *i=opaque;if(whence&AVSEEK_SIZE)return i->length;
 whence &= ~AVSEEK_FORCE;
 int64_t base=whence==SEEK_SET?0:whence==SEEK_CUR?i->position:whence==SEEK_END?i->length:-1;
 if(base<0 || pos < -base || pos > i->length-base)return AVERROR(EINVAL);
 i->position=(int)(base+pos);return i->position;
}
static int deny_open(AVFormatContext *f,AVIOContext **io,const char *url,int flags,AVDictionary **options) {
 return AVERROR(EACCES);
}
static void no_log(void *p,int level,const char *format,va_list ap){}
static int dimensions(int w,int h) {
 return w>0 && h>0 && w<=4096 && h<=4096 && (int64_t)w*h<=8000000;
}
static int emit(AVFrame *frame,AVStream *stream,struct SwsContext **scale) {
 if(!dimensions(frame->width,frame->height))return -1;
 source_work+=(uint64_t)frame->width*frame->height;
 if(source_work>200000000 || ++count>400 || (still_image && count>1))return -1;
 int longest=frame->width>frame->height?frame->width:frame->height;
 int side=still_image?1024:256;
 uint32_t w=longest<=side?frame->width:(frame->width*side+longest/2)/longest;
 uint32_t h=longest<=side?frame->height:(frame->height*side+longest/2)/longest;
 if(!w)w=1;if(!h)h=1;
 if(count==1){width=w;height=h;}else if(w!=width||h!=height)return -1;
 if((uint64_t)w*h*count>12000000)return -1;
 uint32_t bytes=4+4*w*h;
 if(outlen>MAX_OUTPUT-bytes)return -1;
 uint8_t *next=av_realloc(output,outlen+bytes);
 if(!next)return -1;output=next;
 int64_t pts=frame->best_effort_timestamp;
 if(pts!=AV_NOPTS_VALUE && previous_pts!=AV_NOPTS_VALUE && pts>previous_pts) {
  int64_t ms=av_rescale_q(pts-previous_pts,stream->time_base,(AVRational){1,1000});
  if(ms<1||ms>655350)return -1;
  AV_WB32(output+last_offset,ms);
 }
 int64_t ms=frame->duration>0?av_rescale_q(frame->duration,stream->time_base,(AVRational){1,1000}):100;
 if(ms<20)ms=20;if(ms>655350)return -1;
 previous_pts=pts;last_offset=outlen;AV_WB32(output+outlen,ms);
 *scale=sws_getCachedContext(*scale,frame->width,frame->height,frame->format,w,h,AV_PIX_FMT_ARGB,
  SWS_BILINEAR,NULL,NULL,NULL);
 if(!*scale)return -1;
 uint8_t *dst[4]={output+outlen+4,NULL,NULL,NULL};int stride[4]={(int)w*4,0,0,0};
 if(sws_scale(*scale,(const uint8_t *const*)frame->data,frame->linesize,0,frame->height,dst,stride)!=(int)h)return -1;
 outlen+=bytes;return 0;
}
static int receive(AVCodecContext *codec,AVFrame *frame,AVStream *stream,struct SwsContext **scale) {
 int result;
 while((result=avcodec_receive_frame(codec,frame))>=0) {
  if(emit(frame,stream,scale)<0)return -1;av_frame_unref(frame);
 }
 return result==AVERROR(EAGAIN)||result==AVERROR_EOF?0:-1;
}
__attribute__((export_name("decode_media"))) int decode_media(uint8_t *data,int length) {
 if(length<8||length>MAX_INPUT)return -1;
 const char *name=NULL;
 if(!memcmp(data,"GIF87a",6)||!memcmp(data,"GIF89a",6))name="gif";
 else if(!memcmp(data,"\x89PNG\r\n\x1a\n",8))name="png_pipe";
 else if(data[0]==255 && data[1]==216 && data[2]==255)name="jpeg_pipe";
 else if(length>=12 && !memcmp(data,"RIFF",4) && !memcmp(data+8,"WEBP",4)) {
  if(AV_RL32(data+4)!=(uint32_t)length-8)return -1;
  name=length>=21 && !memcmp(data+12,"VP8X",4) && (data[20]&2)?"webp_anim":"webp_pipe";
 }
 else if(length>=12 && !memcmp(data+4,"ftyp",4))name="mov";
 else return -1;
 // Only fixed still-image demuxers get extra detail; animations retain their original bounds.
 still_image=!strcmp(name,"png_pipe")||!strcmp(name,"jpeg_pipe")||!strcmp(name,"webp_pipe");
 av_log_set_callback(no_log);av_max_alloc(64*1024*1024);
 Input input={data,length,0};
 AVFormatContext *format=avformat_alloc_context();
 AVIOContext *io=NULL;AVCodecContext *codec=NULL;AVFrame *frame=NULL;AVPacket *packet=NULL;
 struct SwsContext *scale=NULL;AVDictionary *options=NULL;int status=-1;
 uint8_t *buffer=av_malloc(32768);if(!format||!buffer)goto end;
 io=avio_alloc_context(buffer,32768,0,&input,read_bytes,NULL,seek_bytes);
 if(!io){av_free(buffer);goto end;}
 format->pb=io;format->flags|=AVFMT_FLAG_CUSTOM_IO;format->io_open=deny_open;
 format->probesize=MAX_INPUT;format->max_analyze_duration=1000000;
 av_dict_set(&options,"protocol_whitelist","",0);
 av_dict_set(&options,"format_whitelist",name,0);
 av_dict_set(&options,"codec_whitelist","gif,png,mjpeg,h264,webp,webp_anim",0);
 if(avformat_open_input(&format,NULL,av_find_input_format(name),&options)<0)goto end;
 if(avformat_find_stream_info(format,NULL)<0 || format->nb_streams>16)goto end;
 int stream_index=av_find_best_stream(format,AVMEDIA_TYPE_VIDEO,-1,-1,NULL,0);
 if(stream_index<0)goto end;
 AVStream *stream=format->streams[stream_index];
 enum AVCodecID id=stream->codecpar->codec_id;
 if(id!=AV_CODEC_ID_GIF && id!=AV_CODEC_ID_PNG && id!=AV_CODEC_ID_MJPEG && id!=AV_CODEC_ID_H264
  && id!=AV_CODEC_ID_WEBP && id!=AV_CODEC_ID_WEBP_ANIM)goto end;
 if(!dimensions(stream->codecpar->width,stream->codecpar->height))goto end;
 codec=avcodec_alloc_context3(avcodec_find_decoder(id));
 if(!codec || avcodec_parameters_to_context(codec,stream->codecpar)<0)goto end;
 codec->thread_count=1;codec->max_pixels=8000000;
 if(avcodec_open2(codec,NULL,NULL)<0)goto end;
 output=av_malloc(16);if(!output)goto end;
 outlen=16;frame=av_frame_alloc();packet=av_packet_alloc();if(!frame||!packet)goto end;
 int packets=0,result;
 while((result=av_read_frame(format,packet))>=0) {
  if(++packets>4096)goto end;
  if(packet->stream_index==stream_index) {
   if(avcodec_send_packet(codec,packet)<0 || receive(codec,frame,stream,&scale)<0)goto end;
  }
  av_packet_unref(packet);
 }
 if(result!=AVERROR_EOF)goto end;
 if(avcodec_send_packet(codec,NULL)<0 || receive(codec,frame,stream,&scale)<0 || count<1)goto end;
 AV_WB32(output,0x53494d31);AV_WB32(output+4,width);AV_WB32(output+8,height);AV_WB32(output+12,count);
 status=0;
end:
 av_dict_free(&options);sws_freeContext(scale);
 av_packet_free(&packet);av_frame_free(&frame);avcodec_free_context(&codec);
 if(format)avformat_close_input(&format);
 if(io){av_freep(&io->buffer);avio_context_free(&io);}
 if(status){av_freep(&output);outlen=0;}
 av_free(data);return status;
}
