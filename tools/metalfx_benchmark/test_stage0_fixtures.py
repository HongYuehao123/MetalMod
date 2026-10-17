#!/usr/bin/env python3
"""Reject invalid benchmark configurations before copying worlds or starting a client."""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent


class Stage0Guards(unittest.TestCase):
    def reject(self, args, message):
        result = subprocess.run([sys.executable, str(ROOT / 'run_manual.py'), '--world', 'test',
                                 '--output', '/tmp/metalmod-must-not-create', *args],
                                text=True, capture_output=True)
        self.assertEqual(result.returncode, 2, result.stderr)
        self.assertIn(message, result.stderr)

    def test_explicit_world_and_route_required(self):
        self.reject(['--stage0-fixture', 'surface-rd16'], 'require --submission-benchmark')

    def test_dense_requires_fixed_original_waypoint(self):
        self.reject(['--stage0-fixture', 'dense-rd32', '--submission-benchmark',
                     '--source-world', '/unused', '--anchor-route', '/unused'],
                    '--anchor-waypoint 13 --static-camera')

    def test_surface_cannot_be_static(self):
        self.reject(['--stage0-fixture', 'surface-rd16', '--submission-benchmark',
                     '--source-world', '/unused', '--anchor-route', '/unused', '--static-camera'],
                    'uses the existing smooth 30-second motion')

    def test_distance_conflict(self):
        self.reject(['--stage0-fixture', 'surface-rd16', '--submission-benchmark',
                     '--source-world', '/unused', '--anchor-route', '/unused', '--render-distance', '32'],
                    'render distance conflicts')

    def test_diagnostic_cannot_be_a_completion_control(self):
        self.reject(['--resource-telemetry', '--offscreen-comparison'], 'separate launches')
        spec = importlib.util.spec_from_file_location('offscreen', ROOT / 'analyze_offscreen.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as temp:
            game = Path(temp)
            (game / 'comparison.json').write_text(json.dumps(dict(offscreen_comparison=True,
                                                                 native_only=True, resource_telemetry=True)))
            with self.assertRaisesRegex(ValueError, 'diagnostic'):
                module.summarize(game)


if __name__ == '__main__':
    unittest.main()
