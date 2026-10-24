# SE400 Direct USB / FAT12 — Integrator Notes

Implemented on `codex/worker` after first fast-forwarding `main` (`ba4f5e2`). No push is performed.
Already connected to the existing design-list/viewer→list USB export callbacks, so no separate MainActivity Kotlin wiring is needed.

## Layers and behavior

- `engine/fat12/BlockDevice`: `sectorSize`, `sectorCount`, LBA-based read/write. No Android dependencies.
- `Fat12Volume.mount`: Checks the BPB in sector0 first to recognize a superfloppy. Otherwise, uses the
  first nonempty **primary FAT12(type 01)** partition in the MBR. Rejects GPT/extended partitions and FAT16/32.
  BPB and device sector sizes must match; 512/1024/2048/4096 bytes are supported.
- Provides root file listing, capacity/allocatable free space, fixed root entry count, and new-file writing/reading/deletion.
  Does not provide subfolder creation/browsing, formatting, or repair. Does not delete read-only files/directories.
- Handles odd/even FAT12 12-bit entries and sector-boundary crossings. Updates both copies on volumes with 2 FATs.
  Rejects existing copy mismatches, cross-linked/cyclic root-file chains, and invalid sizes/ranges.
- Write order: data→FAT copies→root entry. Delete order: remove root reference→release FAT.
  Reads back and compares after each transfer, then rereads the entire new file. After I/O failure, discards the session
  without continuing subsequent file writes or automatically retrying. Does not write existing BPB/MBR.
- `ShortNames`: ASCII uppercase 8.3, duplicate suffixes `_1`…, names containing only non-ASCII characters mapped to DESIGN.PES and similar names.
  Does not create LFN. When deleting a file with an existing LFN, also removes only owned slots with verified order/checksum.
- App `LibaumsBlockDevice`: Uses libaums **core 0.10.0** `BlockDeviceDriver`. In this version,
  `ScsiBlockDevice.blocks` is actually the last LBA, so **+1** converts it to sectorCount. Reconfirm this meaning
  when updating versions. Block transfers use chunks of at most 16KiB, with offsets in LBA rather than bytes.
- `DirectUsbAccess`: Supports only class 8 / transparent SCSI 6 / BOT 80 interfaces, bulk IN/OUT, and LUN 0.
  Does not call libaums `UsbMassStorageDevice.init` or Partition/FS factories. Handles USB attach/detach,
  explicit UsbManager permission requests, denial/disconnection/release. Does not write or mount before permission.
- `UsbExporter.drives`: Uses the existing path when a mounted SAF drive exists; otherwise exposes BOT/SCSI targets
  as direct drives. Labels include the actual productName (or VID/PID). A label does not certify SE400 compatibility;
  non-FAT12 devices are not written. Multiple direct targets are distinguished through the existing drive chooser.
- All file conversion, device opening, mounting, writing/deletion/rereading runs on Dispatchers.IO. Only permission/selection UI uses the main thread.
- Checks batch space/root entries first. If insufficient, displays the checklist in `DirectUsbSpaceDialog` with nothing selected
  by default. Deletes only selected PES and enables confirmation only when sufficient space/slots will be available. On reopening,
  does not delete if the USB device ID or BPB/FAT/root fingerprint changed. This is not a fingerprint of all file contents.

Pinned libaums version source:
https://repo.maven.apache.org/maven2/me/jahnen/libaums/core/0.10.0/core-0.10.0-sources.jar
Project: https://github.com/magnusja/libaums
USB permission/interface filtering: https://developer.android.com/develop/connectivity/usb/host
FAT format reference: Microsoft-authored [Microsoft FAT Specification](https://www.scs.stanford.edu/~zyedidia/docs/_other/fat.pdf).

## Validation

Required JVM test tools are dosfstools' `/usr/sbin/mkfs.fat` and `/usr/sbin/fsck.fat`.
Ubuntu CI now includes dosfstools installation and engine/app JVM tests.

```bash
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:test :app:testDebugUnitTest :app:assembleDebug
```

Tests create a 480KiB image in a temporary directory using `mkfs.fat -F 12 -C ... 480`. Generated images and
MBR variants are not committed. Successful write/delete is followed by verifying `fsck.fat -n` exit 0. For MBR variants,
partition contents are extracted into a separate temporary file for fsck, and MBR/preceding-area immutability is checked.

Final run on 2026-10-23: All **196** engine and **110** app JVM tests passed, and assembleDebug succeeded.
New FAT12/USB tests comprise 10 engine + 3 adapter + 2 deletion-plan tests. Inclusion of the libaums license/notices in the APK was also checked.

Checks cover actual PES byte round trips, odd/even/sector-boundary FAT entries, fragmented allocation, FAT copy consistency,
no changes on disk/root-entry exhaustion, 8.3 duplicate names, LFN deletion, rejection of corrupt BPB/short devices/copy mismatches,
rejection of external metadata changes, and blocking further writes after readback comparison failure or failure while writing the second FAT copy.
App JVM tests use a fake SCSI driver to inspect the final sector/16KiB chunks/short reads/engine bridge,
and check default-unselected deletion lists, allowing only selected files, and simultaneous space/root-slot requirements.

## Actual SE400 + Fold7 validation

2026-10-23 alpha26: The user confirmed **direct USB transfer and successful opening of the design on SE400**.
The following is the initial verification procedure; sewing and errors during power/cable disconnection require separate verification.

1. Close other USB file managers and Windows connections, and connect the powered-on SE400 to Fold7.
2. Check that the export drive shows ‘SE400 direct connection · actual device name’ and that the FAT12 BPB
   can be read after granting USB permission. VID/PID, sectorSize/sectorCount, and actual BPB/MBR presence are currently unconfirmed.
3. Transfer one small PES within 100×100, and verify the app's actual 8.3 name/readback-complete message and recognition in the machine's list.
4. When space is insufficient, verify cancellation preserves files, only selected PES are deleted, and retry has sufficient capacity.
5. Explain the measured behavior that files disappear when the machine is powered off. Do not disconnect power/cable during writing.

FAT is not a journaled filesystem, so power/cable disconnection during FAT-copy updates can leave inconsistencies/allocation leaks.
The code does not repair/format. Do not repeatedly write an abnormal volume; reconnect and inspect it.
Actual SE400 firmware/BOT command compatibility, Fold7 USB permission UI, native AAR behavior, and sewing require physical-device verification.

## alpha14 emergency integration: RAM disk compatibility and diagnostics

Based on main `7aa0806`. `:engine:test` **214**, `:app:testDebugUnitTest` **117**, `:app:assembleDebug` passed.
Additional regressions cover nonstandard jumps/OEM/media, BPB/FAT media differences, 16/32-bit BPB capacity clamping,
immutability after the partition, rejection of false MBR recognition, preservation of FAT12 range/FAT size, 512-byte diagnostic hex round trips,
and unchanged buffer positions/communication counts while observing actual BOT responses. Generated image binaries are not committed.

Compatibility warnings are retained in `Fat12Volume.warnings` and displayed in export results. Clamping only limits the accessible
area; BPB/volume labels are not written. Volumes are still rejected if the originally declared cluster count is not FAT12 or the FAT
is too small. Existing file chains outside the actual area are also rejected rather than automatically repaired.

If a physical-device retry fails, send text using **Copy diagnostics** or **Share** in the design list's USB failure dialog.
It includes both Superfloppy/MBR failure reasons, INQUIRY vendor/product/revision, raw READ CAPACITY values/block count/block size,
and sector0 512-byte hex/parsed BPB values. No additional diagnostic SCSI commands are issued; libaums initialization responses are observed.
Items failing before initialization/reading are displayed as unconfirmed.
Physical-device success awaits this text and the user's retest results.

Batch import was separated from emergency work and stored in a git stash named
`WIP batch design import before urgent SE400 diagnostics`. Integrating only this emergency commit permits alpha14 release.

## alpha20 emergency integration: actual MBR length limit

Physical-device diagnostics: pure MBR, boot `0x80`, type `0x01`, start `32`, count `987`,
READ CAPACITY lastLBA `987` / blockCount `988`.
Partition length is limited to **956 sectors (LBA 32..987)** and passed to the existing BPB total clamp.
Starts outside the device, 0 length, FAT/root/data outside the bounds, and out-of-bounds file chains remain rejected.
Limiting partition length leaves MBR/BPB/label bytes unchanged and does not format.

`Fat12Volume.mount(device, onBootSector)` allows observing existing read results before parsing.
USB diagnostics v2 uses this to record separate 512-byte hex/BPB for sector0 and the partition start sector (LBA 32).
The actual BPB is included even if it fails; no additional SCSI reads are issued for diagnostics.

`Se400FirmwareLayoutTest` literally reproduces the provided MBR fields and creates a 988-block device with synthetic BPB/FAT.
Unprovided CHS/boot code is filled with 0. Checks cover PES write→remount→read, filling remaining space to the
last LBA 987, all writes staying within relative sectors 0..955, FAT copy consistency,
immutability of MBR/pre-partition area/BPB/both volume labels, and rejection of invalid starts and corrupt clamped areas.
The actual LBA 32 BPB and machine file recognition will be confirmed in the next physical-device report.

Validation complete: `:engine:test` **240**, `:app:testDebugUnitTest` **156**, `:app:assembleDebug` passed.

## Diagnostics v3 emergency integration: FAT reserved bytes/copy differences and actual 115 clusters

The measured BPB has 512B/sector, 8 sectors/cluster, reserved 1, 2 FATs × 1 sector,
root 512 entries, total 987, hidden 32. FAT1=LBA33, FAT2=LBA34, root=LBA35..66,
data=LBA67. Of the declared 119 clusters, only **115 (FAT ID 2..116)** entirely within the device
are allocated. LBA987 has only one sector left and is not used as a cluster. If any of the four excess slots
**ID 117..120** in either FAT is nonempty, mounting/writing is rejected and the copy/ID/value reported.

Reserved entries `00 00 00` are accepted with a warning. FAT1's reserved bytes are preserved rather than
corrected to normal values. FAT copy differences are also accepted with warnings; when writing files based on FAT1,
both copies are updated identically. Mounting makes no modifications. `unchanged()` compares each copy with its initial snapshot
to distinguish existing differences from external changes after mounting. Further writes in the same session remain prohibited after I/O failure.

`Fat12Volume.MountObserver` observes existing FAT/root reads as well as boot sectors before validation.
Diagnostics v3 includes the first 64 bytes of FAT1/FAT2 and full hex of the first root sector. No additional reads
are sent after failure, so these values are **snapshots at mount time**. No formatting or volume renaming occurs.

Tests reproducing the measured BPB fields verify PES round trips for both reserved-byte cases, filling all 115 clusters,
immutability of reserved bytes/MBR/BPB/labels/LBA987, FAT copy differences/synchronization, rejection of excess allocation in either FAT,
rejection of external FAT changes after mounting, and preservation of raw diagnostic bytes. Unprovided boot code/CHS/OEM
and similar fields are synthetic test values; generated image binaries are not committed.

Validation complete: `:engine:test` **269**, `:app:testDebugUnitTest` **156**, `:app:assembleDebug` passed.

## After alpha26: persistent result dialog and latest 20 records

- Both SAF/direct USB display success/partial failure/failure in a result dialog with an explicit close button rather than a snackbar.
- The default view shows user-facing summaries (actual names/transfer size/remaining space) and actual problems only.
  Technical logs/raw failure reasons appear only in collapsed-by-default ‘Details’ and ‘Copy/share diagnostics’.
- Confirmed SE400 MBR/BPB adjustments, the 115-cluster limit, reserved bytes 00, and derived media differences
  are treated as normal firmware behavior only for that measured geometry. Engine validation/write boundaries are unchanged.
- The history button above the design list shows the latest 20 records by time/file/result. They are persisted by atomically replacing
  app-internal JSON, and unclosed results are restored. After failure, free space is not reread from a potentially damaged session.
- Transfer size uses actual PES output bytes; free space uses remaining clusters in the FAT.
  If the SAF provider does not supply free space, it is displayed as ‘Unknown’.

Validation: `:engine:test` 269, `:app:testDebugUnitTest` 162, `:app:assembleDebug` passed.
Additional JVM checks cover retaining the latest 20 records/closed-state restoration/diagnostic round trips, actual names/sizes/free
space on the measured SE400 layout, name collisions, preservation of completed files/no further I/O on partial write failure, and unexpected FAT-difference warnings.
Using a separate QA applicationId and test records, emulator checks covered en/ko summaries, default collapsed details, retaining results on back/outside touch,
copying diagnostics, preserving closed state after process restart, recent history, and partial-failure screens.
No retransmission to an actual machine was performed with this follow-up UI build. The QA app/per-app language settings were cleaned up.

## Disk usage and original-based 8.3 names

The measured `10120`-byte PES occupies 3 clusters of 4KB (`12288` bytes). Results/history/per-file
details distinguish file bytes from actual allocated bytes, providing short summaries using KB=1024 and precise diagnostics.
Older records remain readable without an allocation field and display ‘Unknown’. Disk free space retains the existing
calculation using FAT allocation state across 115 usable clusters. Tests check free space on an empty disk,
a 10120-byte write, consumption of 1 cluster by a separate 1-byte file, deletion, and remounting.

The direct cause of names being truncated to `EMBROIDE` was `ShortNames.unique` considering only the first 8 characters of the input stem.
`UsbDesignNames` first selects meaningful parts of the original to form 8.3 names, resolving collisions with `~1` and similar suffixes.
The engine was extended only to preserve `~` as a valid FAT character (existing `_1` policy retained).
Original names remain in diagnostics to distinguish cases already given generic names by the provider/input file.

Follow-up validation: 269 engine and 167 app JVM tests and assembleDebug passed. History persistence/restoration is covered by JVM tests;
in the separate QA app's Korean result dialog, ‘File 10KB · Disk usage 12KB (4KB units)’ and 448KB free space
were checked using test records. Cross-checking with the actual machine's free-space screen awaits the user's report.
