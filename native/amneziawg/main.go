// WhiteVPN native adapter for the pinned AmneziaWG v3 core.
// SPDX-License-Identifier: Apache-2.0
package main
// #include <stdlib.h>
// int whiteProtectSocket(int fd);
import "C"
import (
 "fmt"
 "sync"
 "github.com/amnezia-vpn/amneziawg-go/v3/conn"
 "github.com/amnezia-vpn/amneziawg-go/v3/device"
 "github.com/amnezia-vpn/amneziawg-go/v3/tun"
 "golang.org/x/sys/unix"
)
type protectedBind struct { conn.Bind }
func (b *protectedBind) Open(port uint16) ([]conn.ReceiveFunc, uint16, error) {
 funcs, actual, err := b.Bind.Open(port)
 if err != nil { return nil, 0, err }
 peek, ok := b.Bind.(conn.PeekLookAtSocketFd)
 if !ok { b.Bind.Close(); return nil, 0, fmt.Errorf("socket protection unavailable") }
 count := 0
 for _, get := range []func()(int,error){peek.PeekLookAtSocketFd4, peek.PeekLookAtSocketFd6} {
  fd, e := get()
  if e != nil || fd < 0 { continue }
  count++
  if C.whiteProtectSocket(C.int(fd)) != 1 { b.Bind.Close(); return nil, 0, fmt.Errorf("socket protection rejected") }
 }
 if count == 0 { b.Bind.Close(); return nil, 0, fmt.Errorf("no protected socket") }
 return funcs, actual, nil
}
type handle struct { dev *device.Device; bind *protectedBind }
var lock sync.Mutex
var current *handle
//export awgTurnOn
func awgTurnOn(name string, fd int32, config string) int32 {
 lock.Lock(); defer lock.Unlock()
 if current != nil { unix.Close(int(fd)); return -1 }
 t, _, err := tun.CreateUnmonitoredTUNFromFD(int(fd))
 if err != nil { unix.Close(int(fd)); return -1 }
 b := &protectedBind{conn.NewStdNetBind()}
 // Never pass raw native diagnostics, keys, endpoint names or UAPI strings to logcat.
 logger := &device.Logger{Verbosef: func(string,...any){}, Errorf: func(string,...any){}}
 d := device.NewDevice(t, b, logger)
 if err = d.IpcSet(config); err != nil { d.Close(); return -1 }
 d.DisableSomeRoamingForBrokenMobileSemantics()
 if err = d.Up(); err != nil { d.Close(); return -1 }
 current = &handle{d,b}
 return 0
}
//export awgTurnOff
func awgTurnOff(id int32) { lock.Lock(); defer lock.Unlock(); if id == 0 && current != nil { current.dev.Close(); current = nil } }
//export awgGetConfig
func awgGetConfig(id int32) *C.char { lock.Lock(); defer lock.Unlock(); if id != 0 || current == nil { return nil }; s,e := current.dev.IpcGet(); if e != nil { return nil }; return C.CString(s) }
//export awgGetSocketV4
func awgGetSocketV4(id int32) int32 { return -1 }
//export awgGetSocketV6
func awgGetSocketV6(id int32) int32 { return -1 }
//export awgVersion
func awgVersion() *C.char { return C.CString("AmneziaWG v3.1.20260814 / WhiteVPN protected worker") }
func main() {}
