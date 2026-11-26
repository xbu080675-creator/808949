(function(){
const key='tavoMultimodal.probe';
const toast=s=>tavo.utils.toast(s);
const reportKey=key+'.network';
const errorText=e=>String(e&&e.name||'Error')+': '+String(e&&e.message||e).replace(/https?:\/\/\S+/g,'[URL]').slice(0,240);
const run=fn=>async()=>{try{await fn()}catch(e){await toast('测试失败：'+errorText(e));}};
const report=async (id,lines)=>{
 const text=lines.join('\n');
 await tavo.variable.set(reportKey+'.'+id,text,'global');
 await toast(lines[lines.length-1]);
};
tavo.plugin.onInputAction('report',run(async()=>{
 const parts=[]; for(const id of ['network','native','generate','post']){const v=await tavo.variable.get(reportKey+'.'+id,'global');if(v)parts.push(v);} const text=parts.join('\n\n');
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
 const lines=['Tavo Multimodal 0.0.3'];
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
 await report('network',lines);
}));
tavo.plugin.onInputAction('native',run(async()=>{
 const lines=['Tavo Multimodal 0.0.3','原生文件下载：固定公开测试地址，不携带密钥'];
 let path;
 try{
 path=await tavo.file.save('tavo-mm-network-probe-'+Date.now()+'.txt','https://example.com/',{scope:'chat'});
 const data=await tavo.file.load(path,{encoding:'utf8'});
 lines.push(data?'原生下载通过；不代表 POST API 可用':'原生下载未读到内容');
 }catch(e){lines.push('原生下载：'+errorText(e));}
 finally{if(path){try{await tavo.file.delete(path);}catch(e){lines.push('清理失败：'+errorText(e));}}}
 await report('native',lines);
}));
let busy=false;
const promptText='Reply with exactly OK.';
const modelTest=(id,fn)=>tavo.plugin.onInputAction(id,run(async()=>{
 if(busy){await toast('已有模型测试运行中');return;}
 busy=true;
 const lines=['Tavo Multimodal 0.0.3'];
 try{await toast('正在测试，可能产生少量 API 费用');await fn(lines);}
 catch(e){lines.push('失败：'+errorText(e));}
 finally{busy=false;}
 await report(id,lines);
}));
modelTest('generate',async lines=>{
 const result=await tavo.generate(promptText,{context:false,settings:{maxCompletionTokens:64}});
 lines.push(typeof result==='string'&&result.trim()?'原生模型通过；返回 '+result.length+' 字符':'原生模型未返回文本');
});
modelTest('post',async lines=>{
 const url=String(tavo.plugin.config.get('endpoint')||'').trim();
 const model=String(tavo.plugin.config.get('model')||'').trim();
 const protocol=tavo.plugin.config.get('protocol');
 if(!/^https:\/\/[a-z0-9.-]+(?::[0-9]+)?(?:\/[^\s?#@\\]*)?$/i.test(url)||!model)throw new Error('请填写完整 HTTPS 模型接口和模型名称');
 const anthropic=protocol==='anthropic';
 const body=anthropic?{model,max_tokens:64,messages:[{role:'user',content:promptText}]}:{model,max_tokens:64,messages:[{role:'user',content:promptText}]};
 const headers={'Content-Type':'application/json'};
 if(anthropic)headers['anthropic-version']='2023-06-01';
 const c=typeof AbortController==='function'?new AbortController():null;
 const timer=c&&typeof setTimeout==='function'?setTimeout(()=>c.abort(),20000):null;
 try{
 const r=await fetch(url,{method:'POST',headers,body:JSON.stringify(body),credentials:'omit',redirect:'error',...(c?{signal:c.signal}:{})});
 lines.push('独立 POST HTTP '+r.status+'；无认证，仅测试传输');
 }finally{if(timer!==null)clearTimeout(timer);}
});
})();