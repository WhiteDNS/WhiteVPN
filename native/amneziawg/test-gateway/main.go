// Local-only integration fixture; never packaged in the app.
package main
import (
 "bytes"
 "crypto/rand"
 "encoding/hex"
 "encoding/base64"
 "encoding/json"
 "fmt"
 "io"
 "net"
 "net/http"
 "net/netip"
 "os"
 "time"
 "whitevpn.local/awgtest/netstack"
 "github.com/amnezia-vpn/amneziawg-go/v3/conn"
 "github.com/amnezia-vpn/amneziawg-go/v3/device"
 "golang.org/x/crypto/curve25519"
 "gvisor.dev/gvisor/pkg/tcpip"
 "gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
 "gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
 "gvisor.dev/gvisor/pkg/tcpip/transport/udp"
 "gvisor.dev/gvisor/pkg/waiter"
)
func key()([]byte,[]byte) { k:=make([]byte,32);rand.Read(k);p,_:=curve25519.X25519(k,curve25519.Basepoint);return k,p }
func main(){
 sk,sp:=key();ck,cp:=key()
 t,n,e:=netstack.CreateNetTUN([]netip.Addr{netip.MustParseAddr("10.55.0.1"),netip.MustParseAddr("fd55::1")},nil,1280);if e!=nil{panic(e)}
 s:=n.Stack();s.SetPromiscuousMode(1,true);s.SetSpoofing(1,true)
 f:=tcp.NewForwarder(s,0,1024,func(r *tcp.ForwarderRequest){
  id:=r.ID()
  go func(){
   dst:=net.JoinHostPort(id.LocalAddress.String(),fmt.Sprint(id.LocalPort))
   out,e:=net.DialTimeout("tcp",dst,8*time.Second);if e!=nil{r.Complete(true);return}
   var q waiter.Queue
   ep,err:=r.CreateEndpoint(&q);if err!=nil{out.Close();r.Complete(true);return}
   r.Complete(false);in:=gonet.NewTCPConn(&q,ep)
   go func(){io.Copy(out,in);out.Close();in.Close()}()
   io.Copy(in,out);in.Close();out.Close()
  }()
 });s.SetTransportProtocolHandler(tcp.ProtocolNumber,f.HandlePacket)
 u:=udp.NewForwarder(s,func(r *udp.ForwarderRequest){
  id:=r.ID();var q waiter.Queue
  ep,err:=r.CreateEndpoint(&q);if err!=nil{return}
  in:=gonet.NewUDPConn(s,&q,ep)
  go func(){defer in.Close();out,e:=net.DialTimeout("udp",net.JoinHostPort(id.LocalAddress.String(),fmt.Sprint(id.LocalPort)),8*time.Second);if e!=nil{return};defer out.Close()
   go func(){io.Copy(out,in);out.Close()}();io.Copy(in,out)
  }()
 });s.SetTransportProtocolHandler(udp.ProtocolNumber,u.HandlePacket)
 logger:=&device.Logger{Verbosef:func(string,...any){},Errorf:func(string,...any){}}
 d:=device.NewDevice(t,conn.NewStdNetBind(),logger)
 // Legacy obfuscation parameters also exercise backwards-compatible profiles in the v3 core.
 obfuscation:="jc=4\njmin=40\njmax=70\ns1=20\ns2=30\nh1=12345\nh2=23456\nh3=34567\nh4=45678\n"
 e=d.IpcSet("private_key="+hex.EncodeToString(sk)+"\nlisten_port=55182\n"+obfuscation+"replace_peers=true\npublic_key="+hex.EncodeToString(cp)+"\nallowed_ip=10.55.0.2/32\nallowed_ip=fd55::2/128\n");if e!=nil{panic(e)};if e=d.Up();e!=nil{panic(e)}
 dns,_:=n.ListenUDPAddrPort(netip.MustParseAddrPort("10.55.0.1:53"))
 go func(){b:=make([]byte,65535);for{count,addr,e:=dns.ReadFrom(b);if e!=nil{return};q:=append([]byte(nil),b[:count]...);go func(){req,_:=http.NewRequest("POST","https://cloudflare-dns.com/dns-query",bytes.NewReader(q));req.Header.Set("Content-Type","application/dns-message");c:=http.Client{Timeout:8*time.Second};resp,e:=c.Do(req);if e!=nil{return};defer resp.Body.Close();ans,_:=io.ReadAll(io.LimitReader(resp.Body,65535));dns.WriteTo(ans,addr)}()}}()
 tcpEcho,_:=n.ListenTCPAddrPort(netip.MustParseAddrPort("10.55.0.1:4444"))
 go func(){for{c,e:=tcpEcho.Accept();if e!=nil{return};go func(){defer c.Close();io.Copy(c,c)}()}}()
 udpEcho,_:=n.ListenUDPAddrPort(netip.MustParseAddrPort("10.55.0.1:4444"))
 go func(){b:=make([]byte,2048);for{n,a,e:=udpEcho.ReadFrom(b);if e!=nil{return};udpEcho.WriteTo(b[:n],a)}}()
 conf:="[Interface]\nPrivateKey = "+base64.StdEncoding.EncodeToString(ck)+"\nAddress = 10.55.0.2/32, fd55::2/128\nDNS = 10.55.0.1\nMTU = 1280\nJc = 4\nJmin = 40\nJmax = 70\nS1 = 20\nS2 = 30\nH1 = 12345\nH2 = 23456\nH3 = 34567\nH4 = 45678\n[Peer]\nPublicKey = "+base64.StdEncoding.EncodeToString(sp)+"\nAllowedIPs = 0.0.0.0/0, ::/0\nEndpoint = 10.0.2.2:55182\nPersistentKeepalive = 5\n"
 // These are freshly generated fixture keys, not user credentials.
 os.WriteFile("client.conf",[]byte(conf),0600)
 status,_:=json.Marshal(map[string]any{"ready":true,"port":55182,"tcp":4444,"udp":4444});fmt.Println(string(status))
 for {time.Sleep(time.Hour)}
 _ = tcpip.FullAddress{}
}
