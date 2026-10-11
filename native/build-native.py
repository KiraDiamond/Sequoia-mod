"""Build one native platform on a matching OS/architecture runner."""
import argparse,os,pathlib,platform,shutil,subprocess
root=pathlib.Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser();parser.add_argument("platform");args=parser.parse_args()
targets={"windows-x86_64":"x86_64-pc-windows-msvc","windows-aarch64":"aarch64-pc-windows-msvc","linux-x86_64":"x86_64-unknown-linux-musl","linux-aarch64":"aarch64-unknown-linux-musl","macos-x86_64":"x86_64-apple-darwin","macos-aarch64":"aarch64-apple-darwin"}
target=targets[args.platform]
host={"Windows":"windows","Linux":"linux","Darwin":"macos"}[platform.system()]
arch={"amd64":"x86_64","x86_64":"x86_64","arm64":"aarch64","aarch64":"aarch64"}[platform.machine().lower()]
assert args.platform==host+"-"+arch,"Build and execute on matching native runner"
env=dict(os.environ)
if host=="windows":env["RUSTFLAGS"]="-C target-feature=+crt-static"
if host=="macos":env["MACOSX_DEPLOYMENT_TARGET"]="13.0"
manifest=str(root/"native/decoder/Cargo.toml")
def run(*cmd):subprocess.run(list(cmd),cwd=root,env=env,check=True)
run("rustup","target","add",target)
run("cargo","run","--release","--locked","--manifest-path",manifest,"--example","precompile","--",args.platform)
run("cargo","test","--release","--locked","--manifest-path",manifest,"--bin","seqimages-decoder")
run("cargo","build","--release","--locked","--manifest-path",manifest,"--target",target,"--bins")
target_dir=pathlib.Path(env.get("CARGO_TARGET_DIR",root/"native/decoder/target"))/target/"release"
out=root/"build/native"/args.platform;out.mkdir(parents=True,exist_ok=True)
exe="seqimages-decoder"+(".exe" if host=="windows" else "")
shutil.copy2(target_dir/exe,out/exe)
shutil.copy2(root/"native/wasm"/(args.platform+".cwasm"),out/"decoder.cwasm")
if host=="windows":
 vswhere=pathlib.Path(os.environ["ProgramFiles(x86)"])/"Microsoft Visual Studio/Installer/vswhere.exe"
 install=subprocess.check_output([str(vswhere),"-latest","-products","*","-property","installationPath"],text=True).strip()
 dev=pathlib.Path(install)/"Common7/Tools/VsDevCmd.bat"
 cmd=out/"build-broker.cmd"
 arch_arg="arm64" if arch=="aarch64" else "x64"
 cmd.write_text('@echo off\ncall "'+str(dev)+'" -arch='+arch_arg+' -host_arch='+arch_arg+'\nif errorlevel 1 exit /b 1\ncl /nologo /std:c++17 /O2 /MT /EHsc "'+str(root/"native/sandbox.cpp")+'" /Fe:"'+str(out/"sandbox.exe")+'" /Fo:"'+str(out/"sandbox.obj")+'" /link /DYNAMICBASE /NXCOMPAT /HIGHENTROPYVA\n')
 with cmd.open("a") as stream:
  stream.write('if errorlevel 1 exit /b 1\ncl /nologo /std:c++17 /O2 /MT /EHsc "'+str(root/"native/windows-probe.cpp")+'" /Fe:"'+str(out/"sandbox-probe.exe")+'" /Fo:"'+str(out/"probe.obj")+'" /link /DYNAMICBASE /NXCOMPAT /HIGHENTROPYVA\n')
 run("cmd","/c",str(cmd))
 cmd.unlink();(out/"sandbox.obj").unlink();(out/"probe.obj").unlink()
if host=="macos":run("codesign","--force","--sign","-",str(out/exe))
run("python",str(root/"native/verify-native.py"),args.platform,str(out),str(target_dir))
