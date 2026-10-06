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

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--world',required=True)
p.add_argument('--output',type=pathlib.Path,required=True)
p.add_argument('--submission-benchmark',action='store_true',help='Run automated 30s native ABBA; spatial A/B requires --compare-spatial')
p.add_argument('--comparison-property',choices=['metalmod.commandBatching','metalmod.fusedDraws','metalmod.bufferOffsets'],default='metalmod.commandBatching')
p.add_argument('--source-world',type=pathlib.Path,help='Read-only saved-world source override')
p.add_argument('--anchor-route',type=pathlib.Path)
p.add_argument('--anchor-waypoint',type=int,default=0)
p.add_argument('--static-camera',action='store_true')
p.add_argument('--diagnostic-only',action='store_true',help='Two enabled captures for profiling, not an A/B comparison')
p.add_argument('--gpu-stage-timing',action='store_true',help='Diagnostic counters; compare throughput separately')
p.add_argument('--renderer-comparison',choices=['vanilla','sodium'],help='Fixed renderer in a fresh JVM; native capture (optional spatial), no property A/B')
p.add_argument('--offscreen-comparison',action='store_true',help='Test-only bounded present/offscreen ABBA; real GPU completion fences in both modes')
p.add_argument('--compare-spatial',action='store_true',help='Explicit feature experiment: also capture spatial MetalFX; default performance tests are native only')
p.add_argument('--render-distance',type=int,default=32,help='Record explicit terrain distance; 32 is the existing stress fixture, mcopt footage uses 16')
p.add_argument('--extra-mod',type=pathlib.Path,action='append',default=[])
p.add_argument('--sodium-options',type=pathlib.Path,help='Copied-instance Sodium options override for controlled parity diagnostics')
p.add_argument('--display',choices=['current','internal','external'],default='external',help='Place only the copied benchmark window on the selected screen before warmup; internal fits visible screen')
p.add_argument('--window-width',type=int,default=2560,help='Initial logical content width')
p.add_argument('--window-height',type=int,default=1440,help='Initial logical content height')
a=p.parse_args()
if a.window_width<=0 or a.window_height<=0:p.error('positive window dimensions required')
if not 2<=a.render_distance<=32:p.error('render distance must be 2..32')
if a.compare_spatial and not a.submission_benchmark:p.error('spatial comparison requires --submission-benchmark')
if a.offscreen_comparison and (not a.submission_benchmark or a.compare_spatial or a.diagnostic_only or a.gpu_stage_timing):
    p.error('offscreen comparison requires native submission benchmark without GPU-stage instrumentation')
if (a.renderer_comparison or a.diagnostic_only or a.static_camera or a.gpu_stage_timing or a.anchor_route) and not a.submission_benchmark:
    p.error('diagnostic/anchor options require --submission-benchmark')
repo=pathlib.Path(__file__).resolve().parents[2]
instance=pathlib.Path.home()/'Documents/.minecraft/versions/MetalMod_Test_26.2'
source=a.source_world if a.source_world else instance/'saves'/a.world
if a.world in ('.','..') or pathlib.Path(a.world).name!=a.world or not source.is_dir() or a.output.exists():p.error('existing source world and new output directory required')
if a.anchor_route:
    route=json.loads(a.anchor_route.read_text())
    if not 0<=a.anchor_waypoint<len(route['waypoints']):p.error('anchor waypoint out of range')
    point=route['waypoints'][a.anchor_waypoint]
    if point['dimension']!='minecraft:overworld':p.error('anchor must be Overworld')
    pos=[point[k] for k in ('x','y','z')];rot=[point[k] for k in ('yaw','pitch')]
else:
    player=nbt(max((source/'players/data').glob('*.dat'),key=lambda x:x.stat().st_mtime))
    pos=player.get('Pos',player.get('pos'));rot=player.get('Rotation',player.get('rotation'))
    if pos is None or rot is None:raise ValueError('saved position absent')
subprocess.run(['bash','tools/metalfx_benchmark/build_addon.sh'],cwd=repo,check=True)
probe=repo/'build/metalfx-benchmark/display-probe.dylib'
subprocess.run(['xcrun','clang++','-std=c++17','-fobjc-arc','-dynamiclib','tools/metalfx_benchmark/display_probe.mm','-framework','Metal','-framework','AppKit','-framework','CoreGraphics','-framework','QuartzCore','-o',str(probe)],cwd=repo,check=True)
out=a.output.resolve();out.mkdir();(out/'.metalmod-benchmark-copy').touch()
shutil.copytree(source,out/'saves'/a.world);shutil.copytree(instance/'config',out/'config')
if a.sodium_options:
    # Explicit copied-instance override only; never write to the source game's configuration.
    json.loads(a.sodium_options.read_text())
    shutil.copy2(a.sodium_options,out/'config/sodium-options.json')
import re
config=out/'config/metalmod.properties'
content=config.read_text()
for key in ('enableFrameGeneration','enableTemporalUpscaling','enableSuperResolution'):
    content,n=re.subn(r'(?m)^'+key+r'=.*$',key+'=false',content)
    if not n:content+='\n'+key+'=false\n'
config.write_text(content)
settings={'renderDistance':str(a.render_distance),'simulationDistance':'5','fullscreen':'true','maxFps':'260','enableVsync':'false','soundCategory_master':'0.0','overrideWidth':str(a.window_width),'overrideHeight':str(a.window_height)}
if a.submission_benchmark:settings.update(fullscreen='false',enableVsync='false')
lines=[l for l in (instance/'options.txt').read_text().splitlines() if l.split(':',1)[0] not in settings]
(out/'options.txt').write_text('\n'.join(lines+[f'{k}:{v}' for k,v in settings.items()])+'\n')
(out/'mods').mkdir()
for jar in (instance/'mods').glob('*.jar'):
    if 'metalmod' not in jar.name.lower():shutil.copy2(jar,out/'mods'/jar.name)
shutil.copy2(repo/'build/libs/metalmod-1.0.0.jar',out/'mods/metalmod-1.0.0.jar')
for jar in a.extra_mod:
    if not jar.is_file() or (out/'mods'/jar.name).exists():p.error('extra mod missing or conflicts: '+str(jar))
    shutil.copy2(jar,out/'mods'/jar.name)
if a.submission_benchmark:
    import hashlib
    (out/'comparison.json').write_text(json.dumps({'offscreen_comparison':a.offscreen_comparison,'property':a.comparison_property,
        'diagnostic_only':a.diagnostic_only,'static_camera':a.static_camera,'anchor':dict(position=pos,rotation=rot),
        'source_world':str(source),'anchor_route':str(a.anchor_route) if a.anchor_route else None,
        'anchor_waypoint':a.anchor_waypoint,'gpu_stage_timing':a.gpu_stage_timing,
        'renderer':a.renderer_comparison,'native_only':not a.compare_spatial,'render_distance':a.render_distance,
        'vsync':False,'fps_limit':'unlimited (vanilla 260 sentinel)','frame_generation':False,'temporal_upscaling':False,
        'requested_display':a.display,'initial_logical_size':[a.window_width,a.window_height],'extra_mod_sha256':{j.name:hashlib.sha256(j.read_bytes()).hexdigest() for j in a.extra_mod},
        'sodium_options_sha256':hashlib.sha256(a.sodium_options.read_bytes()).hexdigest() if a.sodium_options else None,
        'jar_sha256':hashlib.sha256((out/'mods/metalmod-1.0.0.jar').read_bytes()).hexdigest()},indent=2))
shutil.copy2(repo/'build/metalfx-benchmark/spatial-benchmark.jar',out/'mods/movement-diagnostic.jar')
r=out/'debug/metalmod/routes';r.mkdir(parents=True)
(r/('minecraft.overworld.json' if a.submission_benchmark else 'unused-anchor.json')).write_text(json.dumps({'name':'minecraft.overworld','waypoints':[dict(dimension='minecraft:overworld',x=pos[0],y=pos[1],z=pos[2],yaw=rot[0],pitch=rot[1],dwellMs=60000)]},indent=2))
cp=subprocess.check_output(['python3','scripts/build_classpath.py',str(instance)],cwd=repo,text=True).strip();cp=':'.join(x for x in cp.split(':') if '/mods/' not in x)
command=['/Library/Java/JavaVirtualMachines/jdk-26.jdk/Contents/Home/bin/java','-XstartOnFirstThread','-Xmx4G','--enable-native-access=ALL-UNNAMED','-Dmetalmod.metalBackend=true','-Dmetalmod.motionBenchmark=true' if a.submission_benchmark else '-Dmetalmod.manualRecording=true','-Dmetalmod.capturePrep=false','-Dmetalmod.displayProbe='+str(probe),'-cp',cp,'net.fabricmc.loader.impl.launch.knot.KnotClient','--username','MetalFXTest','--uuid','00000000000000000000000000000001','--accessToken','0','--version','26.2','--gameDir',str(out),'--assetsDir',str(instance.parents[1]/'assets'),'--assetIndex','32','--width','2560','--height','1440','--quickPlaySingleplayer',a.world]
command[command.index('--width')+1]=str(a.window_width)
command[command.index('--height')+1]=str(a.window_height)
command.insert(command.index('-cp'),'-Dmetalmod.benchmarkNativeOnly='+str(not a.compare_spatial).lower())
if a.offscreen_comparison:
    command.insert(command.index('-cp'),'-Dmetalmod.offscreenComparison=true')
if a.submission_benchmark:
    command.insert(command.index('-cp'),'-Dmetalmod.submissionBenchmark=true')
    command.insert(command.index('-cp'),'-Dmetalmod.comparisonProperty='+a.comparison_property)
if a.diagnostic_only:command.insert(command.index('-cp'),'-Dmetalmod.benchmarkDiagnostic=true')
if a.static_camera:command.insert(command.index('-cp'),'-Dmetalmod.benchmarkStaticCamera=true')
if a.gpu_stage_timing:command.insert(command.index('-cp'),'-Dmetalmod.gpuStageTiming=true')
if a.renderer_comparison:command.insert(command.index('-cp'),'-Dmetalmod.rendererComparison='+a.renderer_comparison)
(out/'launch.json').write_text(json.dumps(command,indent=2));env=dict(os.environ);env.pop('MTL_DEBUG_LAYER',None)
env.pop('METALMOD_BENCHMARK_DISPLAY',None)
if a.display!='current':env['METALMOD_BENCHMARK_DISPLAY']=a.display
with (out/'console.log').open('w') as log:
    proc=subprocess.Popen(command,cwd=out,env=env,stdout=log,stderr=subprocess.STDOUT);(out/'pid').write_text(str(proc.pid));print('Running',out,'pid',proc.pid,'anchor',pos,rot,flush=True)
    # Disposable benchmark only; keep the display/user session awake until this client exits.
    # Match the validation runner so an unattended ABBA run does not lose focus to idle lock.
    subprocess.Popen(['/usr/bin/caffeinate','-d','-i','-u','-t','900','-w',str(proc.pid)])
    try:code=proc.wait(timeout=3600)
    except subprocess.TimeoutExpired:proc.terminate();proc.wait(timeout=20);raise SystemExit('timeout')
print('Exit',code);raise SystemExit(code)
