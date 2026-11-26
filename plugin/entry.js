(function(){
const key='tavoMultimodal.probe';
const toast=s=>tavo.utils.toast(s);
const reportKey=key+'.network';
const errorText=e=>String(e&&e.name||'Error')+': '+String(e&&e.message||e).replace(/https?:\/\/\S+/g,'[URL]').slice(0,240);
const run=fn=>async()=>{try{await fn()}catch(e){await toast('测试失败：'+errorText(e));}};
const report=async lines=>{
 const text=lines.join('\n');
 await tavo.variable.set(reportKey,text,'global');
 await toast(lines[lines.length-1]);
};
tavo.plugin.onInputAction('report',run(async()=>{
 const text=await tavo.variable.get(reportKey,'global');
 if(!text){await toast('尚无网络报告');return;}
 await tavo.utils.export('tavo-network-report.txt',text);
}));
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
 const lines=['Tavo Multimodal 0.0.2'];
 let stage='读取配置';
 try{
 const raw=String(tavo.plugin.config.get('endpoint')||'').trim();
 lines.push('配置读取：'+(raw?'非空':'空'));
 stage='检查地址';
 if(!/^https:\/\/[a-z0-9.-]+(?::[0-9]+)?(?:\/[^\s?#@\\]*)?$/i.test(raw))throw new Error('请输入无密钥、无查询参数的 HTTPS 地址');
 lines.push('fetch='+typeof fetch+'; AbortController='+typeof AbortController+'; XMLHttpRequest='+typeof XMLHttpRequest);
 stage='fetch GET';
 if(typeof fetch!=='function')throw new Error('当前环境无 fetch');
 const opts={method:'GET',credentials:'omit',redirect:'error'};
 let timer;
 if(typeof AbortController==='function'&&typeof setTimeout==='function'){
 const controller=new AbortController();opts.signal=controller.signal;timer=setTimeout(()=>controller.abort(),10000);
 }
 try{
 const r=await fetch(raw,opts);
 lines.push('HTTP '+r.status+'；仅验证网络');
 }finally{if(timer!==undefined)clearTimeout(timer);}
 }catch(e){lines.push(stage+'：'+errorText(e));}
 await report(lines);
}));
tavo.plugin.onInputAction('native',run(async()=>{
 const lines=['Tavo Multimodal 0.0.2','原生文件下载：固定公开测试地址，不携带密钥'];
 let path;
 try{
 path=await tavo.file.save('tavo-mm-network-probe-'+Date.now()+'.txt','https://example.com/',{scope:'chat'});
 const data=await tavo.file.load(path,{encoding:'utf8'});
 lines.push(data?'原生下载通过；不代表 POST API 可用':'原生下载未读到内容');
 }catch(e){lines.push('原生下载：'+errorText(e));}
 finally{if(path){try{await tavo.file.delete(path);}catch(e){lines.push('清理失败：'+errorText(e));}}}
 await report(lines);
}));
})();