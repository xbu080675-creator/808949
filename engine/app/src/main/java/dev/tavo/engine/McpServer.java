package dev.tavo.engine;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Small, stateless MCP 2025-03-26 / 2025-06-18 JSON transport. */
public final class McpServer implements AutoCloseable {
    private final String token;
    private final Supplier<Map<String,Object>> status, result, taskStatus, cancel;
    private final ServerSocket server;
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(2,4,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8));
    private final Set<Socket> clients=ConcurrentHashMap.newKeySet();
    public McpServer(int port,String token,Supplier<Map<String,Object>> status,Supplier<Map<String,Object>> result) throws IOException {
        this(port,token,status,result,()->obj("phase","unavailable"),()->obj("cancelRequested",false));
    }
    public McpServer(int port,String token,Supplier<Map<String,Object>> status,Supplier<Map<String,Object>> result,Supplier<Map<String,Object>> taskStatus,Supplier<Map<String,Object>> cancel) throws IOException {
        this.token=Objects.requireNonNull(token);this.status=status;this.result=result;this.taskStatus=taskStatus;this.cancel=cancel;
        if(token.length()<32)throw new IllegalArgumentException("short token");
        server=new ServerSocket();server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),port),8);
        Thread t=new Thread(()->{while(!server.isClosed())try{Socket s=server.accept();clients.add(s);try{pool.execute(()->serve(s));}catch(RejectedExecutionException e){clients.remove(s);s.close();}}catch(IOException ignored){}},"MCP accept");t.setDaemon(true);t.start();
    }
    public int port(){return server.getLocalPort();}
    public void close() throws IOException {server.close();for(Socket s:clients)try{s.close();}catch(IOException ignored){}pool.shutdownNow();}
    static Map<String,Object> obj(Object... pairs){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]);return m;}
    static String json(Object v){if(v==null)return "null";if(v instanceof Number||v instanceof Boolean)return v.toString();if(v instanceof Map){StringJoiner j=new StringJoiner(",","{","}");((Map<?,?>)v).forEach((k,x)->j.add(json(k)+":"+json(x)));return j.toString();}if(v instanceof Iterable){StringJoiner j=new StringJoiner(",","[","]");for(Object x:(Iterable<?>)v)j.add(json(x));return j.toString();}StringBuilder b=new StringBuilder("\"");for(char c:v.toString().toCharArray()){if(c=='"'||c=='\\')b.append('\\').append(c);else if(c<32)b.append(String.format(Locale.ROOT,"\\u%04x",(int)c));else b.append(c);}return b.append('"').toString();}
    static final class Parser {
        final String s; int i; Parser(String s){this.s=s;}
        void ws(){while(i<s.length()&&" \r\n\t".indexOf(s.charAt(i))>=0)i++;}
        char peek(){ws();if(i>=s.length())throw new IllegalArgumentException();return s.charAt(i);}
        void eat(char c){if(peek()!=c)throw new IllegalArgumentException();i++;}
        String str(){eat('"');StringBuilder b=new StringBuilder();while(i<s.length()){char c=s.charAt(i++);if(c=='"')return b.toString();if(c<32)throw new IllegalArgumentException();if(c=='\\'){char e=s.charAt(i++);switch(e){case '"':case '\\':case '/':c=e;break;case 'b':c='\b';break;case 'f':c='\f';break;case 'n':c='\n';break;case 'r':c='\r';break;case 't':c='\t';break;case 'u':c=(char)Integer.parseInt(s.substring(i,i+4),16);i+=4;break;default:throw new IllegalArgumentException();}}b.append(c);}throw new IllegalArgumentException();}
        Object val(int d){if(d>24)throw new IllegalArgumentException();char c=peek();if(c=='"')return str();if(c=='{'){i++;Map<String,Object> m=obj();if(peek()=='}'){i++;return m;}while(true){String k=str();eat(':');if(m.containsKey(k))throw new IllegalArgumentException();m.put(k,val(d+1));if(peek()=='}'){i++;return m;}eat(',');}}if(c=='['){i++;List<Object> a=new ArrayList<>();if(peek()==']'){i++;return a;}while(true){a.add(val(d+1));if(peek()==']'){i++;return a;}eat(',');}}for(String k:new String[]{"true","false","null"})if(s.startsWith(k,i)){i+=k.length();return k.equals("null")?null:Boolean.valueOf(k);}int p=i;while(i<s.length()&&"-+0123456789.eE".indexOf(s.charAt(i))>=0)i++;String n=s.substring(p,i);if(!n.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))throw new IllegalArgumentException();if(n.length()>64)throw new IllegalArgumentException();return new java.math.BigDecimal(n);}
        Object parse(){Object v=val(0);ws();if(i!=s.length())throw new IllegalArgumentException();return v;}
    }
    private static String line(InputStream in,int limit)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();while(b.size()<limit){int c=in.read();if(c<0)throw new EOFException();if(c=='\n'){String s=b.toString("US-ASCII");if(!s.endsWith("\r"))throw new IOException("bad line");return s.substring(0,s.length()-1);}b.write(c);}throw new IOException("header too large");}
    private static void reply(OutputStream out,int code,Object data)throws IOException{byte[] b=data==null?new byte[0]:json(data).getBytes(StandardCharsets.UTF_8);String h="HTTP/1.1 "+code+" "+(code==200?"OK":code==202?"Accepted":"Error")+"\r\nContent-Type: application/json\r\nContent-Length: "+b.length+"\r\nConnection: close\r\nCache-Control: no-store\r\n"+(code==405?"Allow: POST\r\n":"")+"\r\n";out.write(h.getBytes(StandardCharsets.US_ASCII));out.write(b);out.flush();}
    private static Map<String,Object> error(Object id,int code,String msg){return obj("jsonrpc","2.0","id",id,"error",obj("code",code,"message",msg));}
    private static Map<String,Object> ok(Object id,Object v){return obj("jsonrpc","2.0","id",id,"result",v);}
    private static Map<String,Object> tool(String name,String description){return obj("name",name,"description",description,"inputSchema",obj("type","object","properties",obj(),"additionalProperties",false));}
    private void serve(Socket socket){try(Socket s=socket){s.setSoTimeout(5000);InputStream in=new BufferedInputStream(s.getInputStream());OutputStream out=s.getOutputStream();String[] request=line(in,2048).split(" ");Map<String,String> h=new HashMap<>();for(int i=0;;i++){if(i>50)throw new IOException();String l=line(in,4096);if(l.isEmpty())break;int p=l.indexOf(':');if(p<1)throw new IOException();String k=l.substring(0,p).toLowerCase(Locale.ROOT);if(h.put(k,l.substring(p+1).trim())!=null)throw new IOException();}
        String host=h.getOrDefault("host","");if(!host.equals("127.0.0.1:"+port())&&!host.equals("localhost:"+port())){reply(out,403,null);return;}
        if(h.containsKey("origin")||!MessageDigest.isEqual(("Bearer "+token).getBytes(StandardCharsets.UTF_8),h.getOrDefault("authorization","").getBytes(StandardCharsets.UTF_8))){reply(out,h.containsKey("origin")?403:401,null);return;}
        if(request.length!=3||!request[1].equals("/mcp")){reply(out,404,null);return;}if(!request[0].equals("POST")){reply(out,405,null);return;}
        if(h.containsKey("transfer-encoding")||!h.getOrDefault("content-type","").toLowerCase(Locale.ROOT).startsWith("application/json")){reply(out,415,null);return;}
        int n;try{n=Integer.parseInt(h.getOrDefault("content-length","-1"));}catch(NumberFormatException e){n=-1;}if(n<1||n>65536){reply(out,413,null);return;}byte[] body=new byte[n];new DataInputStream(in).readFully(body);
        Object raw;try{raw=new Parser(new String(body,StandardCharsets.UTF_8)).parse();}catch(RuntimeException e){reply(out,400,error(null,-32700,"Parse error"));return;}
        if(!(raw instanceof Map)){reply(out,400,error(null,-32600,"Invalid request"));return;}Map<?,?> r=(Map<?,?>)raw;Object id=r.get("id"),method=r.get("method");if(!"2.0".equals(r.get("jsonrpc"))||!(method instanceof String)||!(id==null||id instanceof Number||id instanceof String)){reply(out,400,error(null,-32600,"Invalid request"));return;}
        Map<?,?> params=r.get("params") instanceof Map?(Map<?,?>)r.get("params"):Collections.emptyMap();if(!r.containsKey("id")){reply(out,202,null);return;}
        Object value;switch((String)method){case "initialize":Object ver=params.get("protocolVersion");value=obj("protocolVersion","2025-03-26".equals(ver)?ver:"2025-06-18","capabilities",obj("tools",obj()),"serverInfo",obj("name","tavo-multimodal-engine","version","0.4.0"));break;case "ping":value=obj();break;
        case "tools/list":value=obj("tools",Arrays.asList(tool("engine_status","Local engine connectivity and model readiness; labels and Whisper are experimental."),tool("get_media_result","Latest explicitly shared local analysis or metadata; labels are approximate."),tool("get_task_status","Read local task progress without accessing private files."),tool("cancel_task","Request cancellation of the current task only if authorized in the engine UI.")));break;
        case "tools/call":Object args=params.get("arguments");if(args!=null&&(!(args instanceof Map)||!((Map<?,?>)args).isEmpty())){reply(out,200,error(id,-32602,"No arguments accepted"));return;}Map<String,Object> v;if("engine_status".equals(params.get("name")))v=status.get();else if("get_media_result".equals(params.get("name")))v=result.get();else if("get_task_status".equals(params.get("name")))v=taskStatus.get();else if("cancel_task".equals(params.get("name")))v=cancel.get();else{reply(out,200,error(id,-32602,"Unknown tool"));return;}value=obj("content",Collections.singletonList(obj("type","text","text",json(v))),"isError",false);break;
        default:reply(out,200,error(id,-32601,"Method not found"));return;}reply(out,200,ok(id,value));
    }catch(Exception ignored){}finally{clients.remove(socket);}}
}
