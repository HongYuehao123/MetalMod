#!/usr/bin/env python3
"""Check baseline rejection, event-rate semantics and stable mod payload fingerprints."""
import csv
import json
from pathlib import Path
import tempfile
import unittest
import zipfile
from analyze_stage0 import validate_frames, presentation_metrics, payload_hash
from analyze_offscreen import summarize as completed_summary


class Stage0Analysis(unittest.TestCase):
    def test_capture_acceptance_and_contamination(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            row = {key: '0' for key in ('paused', 'screen_open', 'pipeline_compiles', 'census_active',
                   'sr_requested', 'sr_effective', 'sr_encodes', 'sr_failures', 'sr_recoveries')}
            row.update(window_active='1', width='5120', height='2664', frame_ns='15000000')
            def write():
                with (folder / 'frames.csv').open('w') as file:
                    writer = csv.DictWriter(file, fieldnames=row)
                    writer.writeheader()
                    writer.writerows([row] * 2000)
            write()
            self.assertEqual(len(validate_frames(folder)), 2000)
            for key, value in (('window_active', '0'), ('sr_effective', '2'), ('pipeline_compiles', '1'),
                               ('frame_ns', '1000000')):
                old = row[key]
                row[key] = value
                write()
                with self.assertRaises(ValueError):
                    validate_frames(folder)
                row[key] = old
            write()
            (folder / 'REJECTED.txt').touch()
            with self.assertRaisesRegex(ValueError, 'rejected'):
                validate_frames(folder)

    def test_events_exclude_zero_and_duplicate_timestamps(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            (folder / 'display.csv').write_text('presented_ns\n0\n10000000\n10000000\n20000000\n0\n30000000\n')
            result = presentation_metrics(folder)
            self.assertEqual(result['unique_events'], 3)
            self.assertEqual(result['events_per_second'], 100)
            self.assertEqual(result['zero_timestamps'], 2)

    def test_offscreen_has_zero_events(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            (folder / 'display.csv').write_text('presented_ns\n0\n0\n')
            self.assertEqual(presentation_metrics(folder)['events_per_second'], 0)

    def test_moving_reference_never_reports_an_optimization_gain(self):
        with tempfile.TemporaryDirectory() as temporary:
            game = Path(temporary)
            metadata = dict(offscreen_comparison=True, native_only=True, static_camera=False,
                            stage0_fixture='surface-rd16', anchor=dict(rotation=[0, 0]))
            (game / 'comparison.json').write_text(json.dumps(metadata))
            root = game / 'debug/metalmod'
            root.mkdir(parents=True)
            names = ['present01', 'offscreen02', 'offscreen03', 'present04']
            (root / 'benchmark-index.tsv').write_text(''.join(n + '\t' + n + '\tfixture\n' for n in names))
            for name in names:
                folder = root / name
                folder.mkdir()
                offscreen = name.startswith('offscreen')
                row = {key: '0' for key in ('paused', 'screen_open', 'pipeline_compiles', 'census_active',
                       'sr_failures', 'sr_recoveries', 'sr_requested', 'sr_encodes', 'sr_effective', 'drawable_wait_ns')}
                row.update(window_active='1', width='5120', height='2664', frame_ns='5000000000',
                           draws='90' if offscreen else '100', player_x='0', player_z='0')
                with (folder / 'frames.csv').open('w') as file:
                    writer = csv.DictWriter(file, fieldnames=row)
                    writer.writeheader()
                    for i in range(6):
                        row.update(player_x=str(i), player_z=str(i))
                        writer.writerow(row)
                (folder / 'motion.txt').write_text('Yaw range: -12..12\nSmooth sinusoid: 30s period\n')
                (folder / 'gpu-completion.json').write_text(json.dumps(dict(submitted=6, completed=6,
                    max_in_flight=2, elapsed_ns=30000000000, completed_fps=.2, offscreen=offscreen)))
                (folder / 'render-thread-cpu.json').write_text('{"mean_ms": 1}')
            with self.assertRaisesRegex(ValueError, 'draw workload'):
                completed_summary(game)
            result = completed_summary(game, moving_reference=True)
            self.assertFalse(result['workload_matched'])
            self.assertNotIn('throughput_increase_percent', result)
            self.assertEqual(len(result['runs']), 4)
            (root / names[0] / 'motion.txt').write_text('Yaw range: -10..10\nSmooth sinusoid: 30s period\n')
            with self.assertRaisesRegex(ValueError, 'camera yaw'):
                completed_summary(game, moving_reference=True)

    def test_mod_fingerprint_ignores_repackaging_timestamp(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            for name, year, payload in (('a', 2025, b'class'), ('b', 2026, b'class'), ('c', 2026, b'changed')):
                info = zipfile.ZipInfo('Example.class', date_time=(year, 1, 1, 0, 0, 0))
                with zipfile.ZipFile(folder / (name + '.jar'), 'w') as jar:
                    jar.writestr(info, payload)
            self.assertEqual(payload_hash(folder / 'a.jar'), payload_hash(folder / 'b.jar'))
            self.assertNotEqual(payload_hash(folder / 'a.jar'), payload_hash(folder / 'c.jar'))


if __name__ == '__main__':
    unittest.main()
