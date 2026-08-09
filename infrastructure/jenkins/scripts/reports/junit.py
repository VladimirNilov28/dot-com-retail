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

                if (
                        test.find('failure') is None
                        and test.find('error') is None
                ):
                    continue


                failed_tests.append(
                    f"- `{test.attrib.get('classname')}.{test.attrib.get('name')}`"
                )


        return {
            'total': total,
            'passed': total - failed - skipped,
            'failed': failed,
            'skipped': skipped,
            'failed_tests': failed_tests[:20]
        }