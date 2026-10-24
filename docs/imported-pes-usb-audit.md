# Preserving imported PES bytes during USB transfer

## Code paths

- Direct USB: `DirectUsbExporter.export` → `DesignFormats.preparePes` → `PesExport.prepare` → `DirectUsbExporter.write` → `Fat12Volume.writeFile`.
- Mounted USB(SAF): `UsbExporter.export` → same `DesignFormats.preparePes` → writes `prepared.bytes` to the output stream.
- With a PES signature(`#PES`), `PesExport.prepare` returns input bytes unchanged after `PecViewportCheck.requireValid`(`converted=false`). Changing V1/V6 output settings does not convert existing PES. Names may change under SAF conflict avoidance or direct USB 8.3 rules.
- PEC/DST and editor projects use `PesWriter` through separate generation/conversion paths. Placing PES in an editing project and regenerating differs from directly transferring the original file.

## Regression tests

`UsbTransferReportTest` prepares existing public fixtures 5(`stops.v1/v6`, MIT EmbroidePy Brother v5/v6·Wilcom v6) with each V1/V6 setting, then uses the actual direct-USB write function and a synthetic SE400 FAT12 device. After independent remounting, reads files and compares all bytes against the originals. Also asserts min x/y=0 and header=actual coordinate range. Checks that negative origins, 1unit header mismatches, and selected-hoop overflow are rejected without re-encoding, preserving original files.

For local-only verification, set `MYEMBRO_USB_AUDIT_INPUTS` to a `/tmp` text file containing exactly 5 absolute paths. Used 5 corpus files satisfying SE400 0–999 bounds and header checks. Private files/path lists/contents are not committed. Anonymous dimensions/byte counts/SHA-256 results are saved only in `/tmp/myembro-imported-usb-audit.txt`.

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 \
MYEMBRO_USB_AUDIT_INPUTS=/tmp/myembro-imported-usb-inputs.txt \
./gradlew :app:testDebugUnitTest --tests '*UsbTransferReportTest'
```

Execution result: `BUILD SUCCESSFUL`, 8 tests / 0 failures / 0 errors. All public 5 and local corpus 5 files matched original bytes after preparation/writing/remounting with each V1/V6 setting. Log: `/tmp/myembro-imported-usb-test.log`.

This test does not execute USB hardware or an Android SAF provider. SAF's shared preparation function and stream input bytes were confirmed through code paths; BOT file writing through FAT12 read-back ran on an in-memory block device. Existing PES violations of P1/P2/P3 are blocked rather than automatically corrected. This verification does not mean generated-design lock/density policy is also applied to existing PES.
