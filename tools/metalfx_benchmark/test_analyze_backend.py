#!/usr/bin/env python3
"""Guard optional-path performance conclusions against mismatched or incomplete runs."""
import copy
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch
import analyze_backend


class BackendComparisonTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.roots = [Path(self.temp.name) / str(index) for index in range(4)]
        self.results = []
        for index, root in enumerate(self.roots):
            (root / 'mods').mkdir(parents=True)
            with zipfile.ZipFile(root / 'mods/movement-diagnostic.jar', 'w') as jar:
                jar.writestr('fixture.class', b'identical test fixture')
            capture = root / 'debug/metalmod/capture'
            capture.mkdir(parents=True)
            (capture / 'frames.csv').write_text('render_passes\n12\n14\n')
            (capture / 'benchmark-controls.json').write_text('{"debug_overlay_guard":true,"debug_overlay_frames":0}')
            on = index in (1, 2)
            self.results.append(dict(comparison=dict(
                jar_sha256='same', anchor=[1, 2, 3], source_world='copy source', source_level_sha256='same',
                static_camera=True, native_only=True, renderer='vanilla',
                backend_properties={key: str(on).lower() for key in analyze_backend.KEYS}),
                runs=[dict(capture='capture', width=5120, height=2664, cpu_samples=20,
                           mean_ms=10 if on else 20, p95_ms=21, p99_ms=22, render_thread_cpu_ms=5,
                           submissions=9, draws=19000)]))

    def compare(self):
        with patch.object(analyze_backend, 'summarize', side_effect=copy.deepcopy(self.results)):
            return analyze_backend.compare(self.roots)

    def test_matched_group_reports_frame_intervals_and_counts(self):
        result = self.compare()
        self.assertEqual(result['groups']['on']['fps'], 100)
        self.assertEqual(result['groups']['off']['fps'], 50)
        self.assertEqual(result['groups']['on']['render_passes'], 13)

    def test_reject_mismatched_workload_artifact_flags_or_output(self):
        original = copy.deepcopy(self.results)
        mutations = [
            lambda r: r['comparison'].update(jar_sha256='different'),
            lambda r: r['comparison'].update(source_level_sha256='different'),
            lambda r: r['comparison'].update(resource_telemetry=True),
            lambda r: r['comparison']['backend_properties'].update({'metalmod.texelCache': 'false'}),
            lambda r: r['comparison']['backend_properties'].update({analyze_backend.KEYS[0]: 'false'}),
            lambda r: r['runs'][0].update(width=1280),
            lambda r: r['runs'][0].update(draws=20000),
            lambda r: r['runs'][0].update(cpu_samples=0),
            lambda r: r['runs'].append(dict(r['runs'][0])),
        ]
        for mutate in mutations:
            with self.subTest(mutation=mutate):
                self.results = copy.deepcopy(original)
                mutate(self.results[1])
                with self.assertRaises(ValueError):
                    self.compare()

    def test_reject_debug_overlay_interruption(self):
        (self.roots[1] / 'debug/metalmod/capture/benchmark-controls.json').write_text(
            '{"debug_overlay_guard":true,"debug_overlay_frames":1}')
        with self.assertRaisesRegex(ValueError, 'debug-overlay'):
            self.compare()

    def test_reject_different_test_addon(self):
        with zipfile.ZipFile(self.roots[1] / 'mods/movement-diagnostic.jar', 'w') as jar:
            jar.writestr('fixture.class', b'different test fixture')
        with self.assertRaisesRegex(ValueError, 'add-on'):
            self.compare()


if __name__ == '__main__':
    unittest.main()
