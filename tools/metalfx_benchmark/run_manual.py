#!/usr/bin/env python3
"""Copy one saved world and launch passive manual diagnostics; never writes to source instance."""
import argparse, gzip, io, json, os, pathlib, shutil, struct, subprocess

def nbt(path):
    f=io.BytesIO(gzip.decompress(path.read_bytes()))
    def n(fmt):return struct.unpack('>'+fmt,f.read(struct.calcsize('>'+fmt)))[0]
    def string():return f.read(n('H')).decode('utf8')
    def val(t):
        if t in (1,2,3,4,5,6):return n({1:'b',2:'h',3:'i',4:'q',5:'f',6:'d'}[t])
        if t==8:return string()
        if t==9:
            c,l=n('b'),n('i');return [val(c) for _ in range(l)]
        if t==10:
            d={}
            while (c:=n('b')):
                k=string();d[k]=val(c)
            return d
        if t in (7,11,12):return [n({7:'b',11:'i',12:'q'}[t]) for _ in range(n('i'))]
        raise ValueError(t)
    n('b');string();return val(10)

p=argparse.ArgumentParser(description=__doc__);p.add_argument('--world',required=True);p.add_argument('--output',type=pathlib.Path,required=True);a=p.parse_args()
repo=pathlib.Path(__file__).resolve().parents[2]
instance=pathlib.Path.home()/'Documents/.minecraft/versions/MetalMod_Test_26.2'
source=instance/'saves'/a.world
if source.parent!=instance/'saves' or not source.is_dir() or a.output.exists():p.error('existing source world and new output directory required')
player=nbt(max((source/'players/data').glob('*.dat'),key=lambda x:x.stat().st_mtime))
pos=player.get('Pos',player.get('pos'));rot=player.get('Rotation',player.get('rotation'))
if pos is None or rot is None:raise ValueError('saved position absent')
subprocess.run(['bash','tools/metalfx_benchmark/build_addon.sh'],cwd=repo,check=True)
probe=repo/'build/metalfx-benchmark/display-probe.dylib'
subprocess.run(['xcrun','clang++','-std=c++17','-fobjc-arc','-dynamiclib','tools/metalfx_benchmark/display_probe.mm','-framework','Metal','-framework','AppKit','-o',str(probe)],cwd=repo,check=True)
out=a.output.resolve();out.mkdir();(out/'.metalmod-benchmark-copy').touch()
shutil.copytree(source,out/'saves'/a.world);shutil.copytree(instance/'config',out/'config')
settings={'renderDistance':'32','simulationDistance':'5','fullscreen':'true','maxFps':'260','enableVsync':'true','soundCategory_master':'0.0','overrideWidth':'2560','overrideHeight':'1440'}
lines=[l for l in (instance/'options.txt').read_text().splitlines() if l.split(':',1)[0] not in settings]
(out/'options.txt').write_text('\n'.join(lines+[f'{k}:{v}' for k,v in settings.items()])+'\n')
(out/'mods').mkdir()
for jar in (instance/'mods').glob('*.jar'):
    if 'metalmod' not in jar.name.lower():shutil.copy2(jar,out/'mods'/jar.name)
shutil.copy2(repo/'build/libs/metalmod-1.0.0.jar',out/'mods/metalmod-1.0.0.jar')
shutil.copy2(repo/'build/metalfx-benchmark/spatial-benchmark.jar',out/'mods/movement-diagnostic.jar')
r=out/'debug/metalmod/routes';r.mkdir(parents=True)
(r/'unused-anchor.json').write_text(json.dumps({'name':'minecraft.overworld','waypoints':[dict(dimension='minecraft:overworld',x=pos[0],y=pos[1],z=pos[2],yaw=rot[0],pitch=rot[1],dwellMs=60000)]},indent=2))
cp=subprocess.check_output(['python3','scripts/build_classpath.py',str(instance)],cwd=repo,text=True).strip();cp=':'.join(x for x in cp.split(':') if '/mods/' not in x)
command=['/Library/Java/JavaVirtualMachines/jdk-26.jdk/Contents/Home/bin/java','-XstartOnFirstThread','-Xmx4G','--enable-native-access=ALL-UNNAMED','-Dmetalmod.metalBackend=true','-Dmetalmod.manualRecording=true','-Dmetalmod.capturePrep=false','-Dmetalmod.displayProbe='+str(probe),'-cp',cp,'net.fabricmc.loader.impl.launch.knot.KnotClient','--username','MetalFXTest','--uuid','00000000000000000000000000000001','--accessToken','0','--version','26.2','--gameDir',str(out),'--assetsDir',str(instance.parents[1]/'assets'),'--assetIndex','32','--width','2560','--height','1440','--quickPlaySingleplayer',a.world]
(out/'launch.json').write_text(json.dumps(command,indent=2));env=dict(os.environ);env.pop('MTL_DEBUG_LAYER',None)
with (out/'console.log').open('w') as log:
    proc=subprocess.Popen(command,cwd=out,env=env,stdout=log,stderr=subprocess.STDOUT);(out/'pid').write_text(str(proc.pid));print('Running',out,'pid',proc.pid,'anchor',pos,rot,flush=True)
    try:code=proc.wait(timeout=3600)
    except subprocess.TimeoutExpired:proc.terminate();proc.wait(timeout=20);raise SystemExit('timeout')
print('Exit',code);raise SystemExit(code)
