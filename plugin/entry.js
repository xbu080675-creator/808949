(function(){
const key='tavoMultimodal.probe';
const toast=s=>tavo.utils.toast(s);
const run=fn=>async()=>{try{await fn()}catch(e){await toast('测试失败：'+(e.code||e.name||'Error'));}};
tavo.plugin.onInputAction('probe',run(async()=>{
 const version=await tavo.app.version();
 const count=await tavo.message.count();
 const stamp=Date.now();
 await tavo.variable.set(key,stamp,'global');
 const ok=(await tavo.variable.get(key,'global'))===stamp;
 await toast('Tavo '+version+'；消息数 '+count+'；存储 '+(ok?'通过':'失败'));
}));
tavo.plugin.onInputAction('media',run(async()=>{
 const files=await tavo.file.import({scope:'tmp',extensions:['mp3','wav','m4a','mp4','webm','ogg'],multiple:false});
 if(!files.length)return;
 const f=files[0];
 try{
 if(f.size>2*1024*1024){await toast('请选择小于 2 MB 的测试文件');return;}
 const data=await tavo.file.load(f.path,{encoding:'base64'});
 await toast('媒体读取：'+(data?'通过':'失败')+'；字节 '+f.size);
 }finally{await tavo.file.delete(f.path);}
}));
tavo.plugin.onInputAction('network',run(async()=>{
 const raw=String(tavo.plugin.config.get('endpoint')||'').trim();
 const url=new URL(raw);
 if(url.protocol!=='https:'||url.username||url.password||url.search||url.hash){await toast('请输入无密钥、无查询参数的 HTTPS 地址');return;}
 if(typeof fetch!=='function'){await toast('当前环境无 fetch');return;}
 const c=new AbortController(); const timer=setTimeout(()=>c.abort(),10000);
 try{const r=await fetch(url.href,{method:'GET',credentials:'omit',redirect:'error',signal:c.signal});await toast('HTTP '+r.status+'；仅验证网络，不验证模型');}
 finally{clearTimeout(timer);}
}));
})();