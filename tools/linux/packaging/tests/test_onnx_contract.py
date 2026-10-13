"""Actual archive contract; fixtures and mocked ELF tools, no app/native loading."""
import hashlib
import pathlib
import sys
import tempfile
import unittest
import zipfile
from unittest.mock import Mock, patch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import onnx_contract as contract


class OnnxPackageContractTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = pathlib.Path(self.tmp.name)
        (self.root / 'lib/app').mkdir(parents=True)
        self.natives = {name: ('fixture ' + name).encode() for name in contract.NATIVES}
        self.resources = {'backdrop-framing/' + name: ('fixture ' + name).encode() for name in contract.RESOURCES}
        self.payload = dict(self.natives, **self.resources)
        patches = [patch.object(contract, 'NATIVES', {n: hashlib.sha256(b).hexdigest() for n, b in self.natives.items()}),
                   patch.object(contract, 'RESOURCES', {n.split('/', 1)[1]: hashlib.sha256(b).hexdigest()
                                                       for n, b in self.resources.items()})]
        for context in patches:
            context.start()
            self.addCleanup(context.stop)

    def write_jar(self, payload=None, filename='fixture.jar'):
        with zipfile.ZipFile(self.root / 'lib/app' / filename, 'w') as jar:
            for name, data in (self.payload if payload is None else payload).items():
                jar.writestr(name, data)

    def elf_tool(self, args, **kwargs):
        path = pathlib.Path(args[-1])
        jni = path.name.endswith('4j_jni.so')
        if args[0] == 'readelf':
            return ('(NEEDED) Shared library: [libonnxruntime.so.1]\n(RUNPATH) [/build/Release]\n' if jni
                    else '(NEEDED) Shared library: [libc.so.6]\n(RUNPATH) [$ORIGIN]\n')
        self.assertEqual('ldd', args[0])
        self.assertEqual(str(path.parent), kwargs['env']['LD_LIBRARY_PATH'])
        self.assertNotIn('LD_PRELOAD', kwargs['env'])
        self.assertEqual(self.natives[contract.NATIVE_PREFIX + 'libonnxruntime.so'],
                         (path.parent / 'libonnxruntime.so.1').read_bytes())
        return f'libonnxruntime.so.1 => {path.parent}/libonnxruntime.so.1 (0x1000)\nlibc.so.6 => /lib/libc.so.6 (0x2000)\n'

    def test_packaged_pair_models_licenses_and_soname_resolution(self):
        self.write_jar()
        abi = Mock()
        with patch.object(contract.subprocess, 'check_output', side_effect=self.elf_tool):
            report = contract.verify_packaged_onnx(self.root, abi)
        self.assertEqual(2, abi.call_count)
        self.assertEqual('CPU', report['provider'])
        self.assertEqual(set(self.payload), set(report['files']))

    def test_each_missing_or_tampered_payload_fails_before_native_inspection(self):
        for name in self.payload:
            for changed in ['missing', 'tampered']:
                with self.subTest(name=name, changed=changed):
                    payload = dict(self.payload)
                    if changed == 'missing':
                        del payload[name]
                    else:
                        payload[name] += b'corrupt'
                    self.write_jar(payload)
                    abi = Mock()
                    with self.assertRaisesRegex(ValueError, 'Missing|tampered'):
                        contract.verify_packaged_onnx(self.root, abi)
                    abi.assert_not_called()

    def test_duplicate_or_foreign_platform_payload_is_rejected(self):
        self.write_jar()
        self.write_jar(self.resources, 'duplicate.jar')
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            contract.verify_packaged_onnx(self.root, Mock())
        (self.root / 'lib/app/duplicate.jar').unlink()
        self.write_jar({'ai/onnxruntime/native/win-x64/onnxruntime.dll': b'foreign'}, 'foreign.jar')
        with self.assertRaisesRegex(ValueError, 'Unexpected ONNX'):
            contract.verify_packaged_onnx(self.root, Mock())

    def test_missing_dependency_or_external_core_is_rejected(self):
        self.write_jar()
        for linked in ['libonnxruntime.so.1 => not found', 'libonnxruntime.so.1 => /external/libonnxruntime.so.1 (0x1000)']:
            def tool(args, **kwargs):
                return linked if args[0] == 'ldd' else self.elf_tool(args, **kwargs)
            with patch.object(contract.subprocess, 'check_output', side_effect=tool), self.assertRaises(ValueError):
                contract.verify_packaged_onnx(self.root, Mock())


class OnnxSourceResourceTest(unittest.TestCase):
    def test_source_models_and_full_notices_match_packaged_contract(self):
        source = pathlib.Path(__file__).resolve().parents[4] / 'composeApp/src/desktopMain/resources/backdrop-framing'
        for name, digest in contract.RESOURCES.items():
            with self.subTest(name=name):
                self.assertEqual(digest, hashlib.sha256((source / name).read_bytes()).hexdigest())


if __name__ == '__main__':
    unittest.main()
