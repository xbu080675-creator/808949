(function(){
'use strict';
const M=1024*1024,MAX=100*M;
const videoLimit=()=>String(tavo.plugin.config.get('videoReadLimit'))==='100'?100*M:50*M;
const fail=(message,code)=>{throw Object.assign(new Error(message),{code});};
function inspect(bytes,signal){
 if(Object.prototype.toString.call(bytes)!=='[object Uint8Array]'||!bytes.length||bytes.length>MAX)fail('媒体字节无效或超过100 MiB','too_large');
 const v=new DataView(bytes.buffer,bytes.byteOffset,bytes.byteLength),n=bytes.length;
 let budget=4096;
 const check=()=>{if(signal?.aborted)throw new Error('媒体处理已取消');if(--budget<0)fail('媒体结构过于复杂','unsupported_format');};
 const tag=(i)=>String.fromCharCode(bytes[i],bytes[i+1],bytes[i+2],bytes[i+3]);
 const u32=i=>v.getUint32(i),u16=i=>v.getUint16(i),u64=i=>u32(i)*4294967296+u32(i+4);
 const validNum=x=>Number.isFinite(x)&&x>=0&&x<1e10?x:null;
 if(n>=12&&tag(0)==='RIFF'&&tag(8)==='WAVE'){
  const end=u32le(4)+8;if(end>n||end<12)fail('WAV 文件不完整','unsupported_format');
  function u32le(i){return v.getUint32(i,true);}
  let fmt=null,data=null;
  for(let i=12;i+8<=end;){check();const size=u32le(i+4),p=i+8;if(p+size>end)fail('WAV 块不完整','unsupported_format');
   if(tag(i)==='fmt '&&size>=16)fmt={formatCode:v.getUint16(p,true),channels:v.getUint16(p+2,true),sampleRate:u32le(p+4),byteRate:u32le(p+8),bits:v.getUint16(p+14,true)};
   if(tag(i)==='data')data=size;i=p+size+(size%2);
  }
  if(!fmt||data===null||!fmt.channels||!fmt.sampleRate||!fmt.byteRate)fail('WAV 缺少有效音频头','unsupported_format');
  return {container:'WAV',durationSeconds:validNum(data/fmt.byteRate),tracks:[{kind:'audio',...fmt}],size:n};
 }
 function boxes(start,end){const out=[];for(let i=start;i<end;){check();if(end-i<8)fail('媒体盒头不完整','unsupported_format');let size=u32(i),head=8;const type=tag(i+4);if(size===1){if(end-i<16)fail('媒体扩展盒头不完整','unsupported_format');size=u64(i+8);head=16;}else if(size===0)size=end-i;if(!Number.isSafeInteger(size)||size<head||size>end-i)fail('媒体盒长度无效','unsupported_format');out.push({type,p:i+head,end:i+size});i+=size;}return out;}
 const child=(list,type)=>list.find(b=>b.type===type);
 const top=boxes(0,n),moov=child(top,'moov');if(!moov)fail('未找到 MP4/MOV/M4A 元数据盒','unsupported_format');
 const inside=boxes(moov.p,moov.end),mvhd=child(inside,'mvhd');let duration=null;
 if(mvhd){const p=mvhd.p,ver=bytes[p],off=ver===1?20:12,wide=ver===1?8:4;if((ver===0||ver===1)&&p+off+4+wide<=mvhd.end){const scale=u32(p+off),d=ver===1?u64(p+off+4):u32(p+off+4);if(scale&&d!==4294967295)duration=validNum(d/scale);}}
 const tracks=[];
 for(const tr of inside.filter(x=>x.type==='trak')){check();const children=boxes(tr.p,tr.end),mdia=child(children,'mdia'),tkhd=child(children,'tkhd');if(!mdia)continue;
  const md=boxes(mdia.p,mdia.end),hdlr=child(md,'hdlr');if(!hdlr||hdlr.p+12>hdlr.end)continue;const kind=tag(hdlr.p+8);if(kind!=='vide'&&kind!=='soun')continue;
  const info={kind:kind==='vide'?'video':'audio'};
  if(kind==='vide'&&tkhd&&tkhd.end-tkhd.p>=84){const w=u32(tkhd.end-8)/65536,h=u32(tkhd.end-4)/65536;if(w>0&&h>0&&w<=16384&&h<=16384){info.width=w;info.height=h;}}
  const minf=child(md,'minf'),stbl=minf&&child(boxes(minf.p,minf.end),'stbl'),stsd=stbl&&child(boxes(stbl.p,stbl.end),'stsd');
  if(stsd&&stsd.p+16<=stsd.end&&u32(stsd.p+4)>0){const entrySize=u32(stsd.p+8),codec=tag(stsd.p+12);if(entrySize>=8&&entrySize<=stsd.end-stsd.p-8&&/^[a-zA-Z0-9 ._-]{4}$/.test(codec))info.codec=codec;}
  tracks.push(info);
 }
 if(!tracks.length)fail('未找到可识别的音视频轨道','unsupported_format');
 return {container:'ISO BMFF / QuickTime',durationSeconds:duration,tracks,size:n};
}
if(tavo.plugin.config.get('localMediaInput')===true)tavo.plugin.registerFileProcessor('local-media-meta',{
 async extract(file,{signal}={}){
  if(signal?.aborted)throw new Error('媒体处理已取消');
  const ext=String(file.name||file.path||'').split('.').pop().toLowerCase();
  const video=['mp4','mov'].includes(ext);
  const accept=(video?200:50)*M,read=video?videoLimit():50*M;
  if(!Number.isSafeInteger(file.size)||file.size<1||file.size>accept)fail('媒体超过附件上限（音频50 MiB，视频200 MiB）','too_large');
  if(file.size>read)return {content:'[大型视频附件：仅文件基本信息；未读取字节，未解析时长、分辨率或轨道，未理解画面及声音。]\n'+JSON.stringify({size:file.size,extension:ext,readStatus:'size-only'})};
  const bytes=await tavo.file.load(file.path,{encoding:'bytes'});if(signal?.aborted)throw new Error('媒体处理已取消');
  if(bytes?.length!==file.size)throw new Error('媒体读取长度不一致');
  const metadata=inspect(bytes,signal);
  return {content:'[媒体附件：本地容器元数据，仅供参考。未解码画面、未提取音频、未识别或转录内容。不得根据元数据声称看过或听过媒体。]\n'+JSON.stringify(metadata)};
 }
});
})();
