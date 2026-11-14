"""Transport checks only; no simulated Minecraft results or gameplay coverage."""
import io
import re
import unittest
from unittest.mock import patch
import urllib.error
import zipfile

from extract import RangeReader


class Response(io.BytesIO):
    status=206


class RangeTests(unittest.TestCase):
    def test_expired_storage_url_refresh_preserves_zip_crc_without_forwarding_authorization(self):
        payload=(bytes(range(256))*9000)+b'actual member boundary'
        output=io.BytesIO()
        with zipfile.ZipFile(output,'w',compression=zipfile.ZIP_STORED) as archive:
            archive.writestr('cases/native-evidence.bin',payload)
        raw=output.getvalue(); requests=[]; refreshes=[]
        def refresh():
            refreshes.append(True)
            return 'https://artifact.invalid/fresh-signed-reference'
        def get(request,timeout):
            self.assertIsNone(request.get_header('Authorization'))
            requests.append(request.full_url)
            if request.full_url.endswith('expired'):
                raise urllib.error.HTTPError(request.full_url,403,'Expired',{},None)
            start,end=map(int,re.fullmatch(r'bytes=(\d+)-(\d+)',request.get_header('Range')).groups())
            return Response(raw[start:end+1])
        reader=RangeReader('https://artifact.invalid/expired',len(raw),refresh=refresh)
        with patch('urllib.request.urlopen',side_effect=get):
            with zipfile.ZipFile(reader) as archive:
                self.assertEqual(archive.read('cases/native-evidence.bin'),payload)
        self.assertEqual(len(refreshes),1)
        self.assertEqual(requests[0],'https://artifact.invalid/expired')
        self.assertGreater(reader.transferred,2*1024**2)


if __name__=='__main__': unittest.main()
