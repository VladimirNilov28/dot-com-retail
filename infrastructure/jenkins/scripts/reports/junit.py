import glob
import os
import xml.etree.ElementTree as ET


class TestReport:

    def __init__(
            self,
            directory
    ):

        self.directory = directory


    def collect(self):

        total = 0
        failed = 0
        skipped = 0

        failed_tests = []


        files = glob.glob(
            os.path.join(
                self.directory,
                '*.xml'
            )
        )


        for file in files:

            try:
                root = ET.parse(file).getroot()

            except ET.ParseError:
                continue


            total += int(
                root.attrib.get(
                    'tests',
                    0
                )
            )


            failed += int(
                root.attrib.get(
                    'failures',
                    0
                )
            )


            failed += int(
                root.attrib.get(
                    'errors',
                    0
                )
            )


            skipped += int(
                root.attrib.get(
                    'skipped',
                    0
                )
            )


            for test in root.iter(
                    'testcase'
            ):

                failure_node = test.find('failure')

                if failure_node is None:
                    failure_node = test.find('error')

                if failure_node is None:
                    continue


                message = (
                        failure_node.attrib.get('message')
                        or (failure_node.text or '').strip().split('\n')[0]
                        or 'No message available'
                )


                failed_tests.append({
                    'name': f"{test.attrib.get('classname')}.{test.attrib.get('name')}",
                    'message': message[:200]
                })


        return {
            'total': total,
            'passed': total - failed - skipped,
            'failed': failed,
            'skipped': skipped,
            'failed_tests': failed_tests[:20]
        }