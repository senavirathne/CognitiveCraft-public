"""Real unittest execution with independently readable per-case XML and raw output."""
import argparse, contextlib, io, time, unittest, xml.etree.ElementTree as ET
from common import HERE, require, registry

def ids(suite):
    for test in suite:
        if isinstance(test,unittest.TestSuite):yield from ids(test)
        else:yield test.id()

def run(stage, output):
    suite=unittest.defaultTestLoader.discover(str(HERE),pattern='test_*.py')
    expected=registry(stage)['python_cases']; discovered=list(ids(suite))
    known={case for spec in __import__('common').read(HERE/'case-registry.json').values() for case in spec['python_cases']}
    require(len(discovered)==len(set(discovered)) and set(discovered)<=known and set(expected)<=set(discovered),'Python registry mismatch')
    def leaves(value):
        for test in value:
            if isinstance(test,unittest.TestSuite):yield from leaves(test)
            else:yield test
    suite=unittest.TestSuite(test for test in leaves(suite) if test.id() in expected)
    actual=list(ids(suite))
    root=ET.Element('testsuite',name='parallel_d',tests=str(len(actual)))
    class Result(unittest.TextTestResult):
        def startTest(self,test):
            super().startTest(test);self.started=time.monotonic();self.node=ET.SubElement(root,'testcase',name=test.id(),classname=test.__class__.__name__)
        def stopTest(self,test):
            self.node.set('time',str(time.monotonic()-self.started));super().stopTest(test)
        def addFailure(self,test,err):
            super().addFailure(test,err);ET.SubElement(self.node,'failure').text=self._exc_info_to_string(err,test)
        def addError(self,test,err):
            super().addError(test,err);ET.SubElement(self.node,'error').text=self._exc_info_to_string(err,test)
        def addSkip(self,test,reason):
            super().addSkip(test,reason);ET.SubElement(self.node,'skipped').text=reason
        def addSubTest(self,test,subtest,err):
            super().addSubTest(test,subtest,err)
            if err:ET.SubElement(self.node,'failure').text=self._exc_info_to_string(err,test)
    stream=io.StringIO()
    result=unittest.TextTestRunner(stream=stream,resultclass=Result,verbosity=2).run(suite)
    ET.SubElement(root,'system-out').text=stream.getvalue()
    output.parent.mkdir(parents=True,exist_ok=True);ET.ElementTree(root).write(output,encoding='utf-8',xml_declaration=True)
    output.with_suffix('.log').write_text(stream.getvalue());print(stream.getvalue())
    require(result.wasSuccessful() and not result.skipped,'mandatory Python checks failed/skipped')

if __name__=='__main__':
    from pathlib import Path
    p=argparse.ArgumentParser();p.add_argument('--stage',required=True);p.add_argument('--output',type=Path,required=True)
    a=p.parse_args();run(a.stage,a.output)
