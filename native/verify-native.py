"""Execute packaged helper and verify exact pixels plus OS sandbox denials."""
import hashlib,json,os,pathlib,socket,struct,subprocess,sys,tempfile,time
root=pathlib.Path(__file__).resolve().parents[1]
name=sys.argv[1];out=pathlib.Path(sys.argv[2]).resolve();target=pathlib.Path(sys.argv[3]).resolve();windows=name.startswith("windows")
helper=out/("seqimages-decoder.exe" if windows else "seqimages-decoder")
media=(root/"src/test/resources/klipy-report.mp4").read_bytes()
with tempfile.TemporaryDirectory(prefix="seqimages-native-test-") as temp:
 scratch=pathlib.Path(temp).resolve()
 command=[str(out/"sandbox.exe"),"20",str(scratch),"1",str(helper),str(helper)] if windows else [str(helper)]
 start=time.perf_counter()
 result=subprocess.run(command,input=struct.pack(">I",len(media))+media,capture_output=True,timeout=25)
 assert result.returncode==0,result.stderr.decode(errors="replace")
 expected="3566824334c607fe33015d853295d2695c9d399aef88d864f2e9a4d329033c2c"
 digest=hashlib.sha256(result.stdout).hexdigest();assert digest==expected,(name,digest,len(result.stdout))
 elapsed=time.perf_counter()-start
 denials=None
 cancellation=None
 if windows:
  sentinel=scratch/"sentinel";sentinel.write_bytes(b"marker")
  assert sentinel.read_bytes()==b"marker"
  assert subprocess.run([os.environ["ComSpec"],"/d","/c","exit"],capture_output=True).returncode==0
  with socket.socket() as listener:
   listener.bind(("127.0.0.1",0));listener.listen(2);listener.settimeout(2)
   port=listener.getsockname()[1]
   with socket.create_connection(("127.0.0.1",port)):pass
   control,_=listener.accept();control.close()
   test=out/"sandbox-probe.exe"
   probe=subprocess.run([str(out/"sandbox.exe"),"10",str(scratch),"1",str(test),str(test),str(sentinel),str(scratch/"denied"),str(port)],capture_output=True,text=True,timeout=15)
   assert probe.returncode==0,(probe.returncode,probe.stdout,probe.stderr)
   assert "APPCONTAINER_DENIALS_OK" in probe.stdout
   denials=probe.stdout.strip()
   listener.settimeout(0.2)
   try:
    unexpected,_=listener.accept();unexpected.close();raise AssertionError("Sandbox reached logger")
   except socket.timeout:pass
  cancellation_test=out/"cancellation-broker.exe"
  cancelled=subprocess.run([sys.executable,str(root/"native/tests/windows-cancellation.py"),str(cancellation_test),str(helper)],capture_output=True,text=True,timeout=20)
  assert cancelled.returncode==0,(cancelled.stdout,cancelled.stderr)
  cancellation=cancelled.stdout.strip()
  cancellation_test.unlink()
  test.unlink()
 if not windows:
  sentinel=scratch/"sentinel";sentinel.write_bytes(b"marker")
  probe=subprocess.run([str(target/"sandbox-probe"),str(sentinel)],capture_output=True,text=True,timeout=5)
  assert probe.returncode==0,(probe.stdout,probe.stderr)
  assert "OS_SANDBOX_DENIALS_OK" in probe.stdout
  denials=probe.stdout.strip()
 bad=subprocess.run(command,input=struct.pack(">I",10)+b"bad",capture_output=True,timeout=25)
 assert bad.returncode!=0 and not bad.stdout
 report={"platform":name,"decode_seconds":elapsed,"pixel_sha256":digest,"pixel_bytes":len(result.stdout),"frames":44,"malformed_input_rejected":True,"os_denials":denials,"broker_cancellation":cancellation,"executables":{f.name:hashlib.sha256(f.read_bytes()).hexdigest() for f in out.iterdir() if f.is_file()}}
 (out/"validation.json").write_text(json.dumps(report,indent=2)+"\n");print(json.dumps(report,indent=2))
