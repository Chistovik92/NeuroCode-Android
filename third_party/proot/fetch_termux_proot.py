#!/usr/bin/env python3
"""Fetch and repackage the Termux (bionic) PRoot fork for bundling in the APK.

Why this exists
---------------
The app ships PRoot as ``app/src/main/jniLibs/<abi>/libproot.so``.  The previous
bundled binary (Alpine ``proot-static``, musl) issues legacy ``stat(4)``/``lstat(6)``
syscalls which are *not* in the Android app seccomp allowlist for 64-bit
(``SYSCALLS.TXT`` maps ``stat``/``lstat`` -> ``newfstatat``(262) on lp64), so it
dies with SIGSYS on Android 15.  The Termux fork links against bionic and calls
``newfstatat``/``fstat`` which are allowed.

The Termux binary is dynamically linked (``libtalloc.so.2``, ``libandroid-shmem.so``,
``libc.so``) and carries a *unbundled* loader (``PROOT_UNBUNDLE_LOADER``).  We:

* rename the versioned ``libtalloc.so.2`` to ``libtalloc.so`` (Android's package
  manager only extracts ``lib*.so`` from ``jniLibs``) and re-point PRoot's NEEDED
  entry and talloc's SONAME at the new name;
* patch PRoot's RUNPATH from ``/data/data/com.termux/files/usr/lib`` to ``$ORIGIN``
  so the dynamic linker finds the two libraries next to ``libproot.so``;
* ship the loader(s) as ``libproot_loader.so`` / ``libproot_loader32.so``; the app
  points PRoot at them via the ``PROOT_LOADER`` / ``PROOT_LOADER_32`` env vars.

Sources
-------
* proot:     ``packages.termux.dev .../p/proot/proot_5.1.107.102_<arch>.deb``
* libtalloc: ``packages.termux.dev .../libt/libtalloc/libtalloc_2.5.0_<arch>.deb``
* libandroid-shmem: ``packages.termux.dev .../liba/libandroid-shmem/libandroid-shmem_0.7_<arch>.deb``

Usage
-----
    python3 third_party/proot/fetch_termux_proot.py

Run from the repository root.  Produces ``app/src/main/jniLibs/<abi>/``.
"""

import io
import os
import struct
import sys
import tarfile
import urllib.request

BASE = "https://packages.termux.dev/apt/termux-main/pool/main"

PROOT_VERSION = "5.1.107.102"
TALLOC_VERSION = "2.5.0"
SHMEM_VERSION = "0.7"

# Android ABI -> Termux/APT arch
ABIS = [
    ("arm64-v8a", "aarch64"),
    ("armeabi-v7a", "arm"),
    ("x86_64", "x86_64"),
]

REPO = os.path.dirname(os.path.abspath(__file__))
JNI = os.path.normpath(os.path.join(REPO, "..", "..", "app", "src", "main", "jniLibs"))

# ELF dynamic-tag constants
DT_NULL = 0
DT_NEEDED = 1
DT_SONAME = 14
DT_RUNPATH = 29
DT_RPATH = 15


def download(url, dest):
    if os.path.exists(dest):
        return
    print("  download", url)
    urllib.request.urlretrieve(url, dest)


def extract_deb_members(deb_path):
    """Return {member_name: bytes} for every file inside a .deb (ar archive)."""
    out = {}
    with open(deb_path, "rb") as f:
        magic = f.read(8)
        if magic != b"!<arch>\n":
            raise SystemExit(f"{deb_path} is not an ar archive")
        while True:
            hdr = f.read(60)
            if len(hdr) < 60:
                break
            name = hdr[0:16].decode("ascii", "replace").rstrip()
            size = int(hdr[48:58].decode("ascii", "replace").strip() or "0")
            data = f.read(size)
            if size % 2:
                f.read(1)
            out[name] = data
    return out


def tar_files(data_blob):
    """Return {path: bytes} for the contents of data.tar.{xz,gz,bz2,...}.

    Member names are normalised: a leading ``./`` (present in Termux .debs)
    is stripped so lookups can use plain paths like
    ``data/data/com.termux/files/usr/bin/proot``.
    """
    result = {}
    with tarfile.open(fileobj=io.BytesIO(data_blob), mode="r:*") as tf:
        for member in tf.getmembers():
            if not member.isfile():
                continue
            name = member.name
            if name.startswith("./"):
                name = name[2:]
            result[name] = tf.extractfile(member).read()
    return result


def deb_files(deb_path):
    """Extract the real payload files from a .deb."""
    members = extract_deb_members(deb_path)
    data_name = next(n for n in members if n.startswith("data.tar"))
    return tar_files(members[data_name])


class Elf:
    """Minimal ELF reader/writer for the dynamic string table (LE only)."""

    def __init__(self, data):
        self.data = bytearray(data)
        if data[:4] != b"\x7fELF":
            raise SystemExit("not an ELF")
        self.is64 = data[4] == 2
        if data[5] != 1:
            raise SystemExit("only little-endian ELF supported")
        endian = "<"
        if self.is64:
            hdr = struct.unpack_from(endian + "HHIQQQIHHHHHH", data, 16)
            (self.e_type, self.e_machine, self.e_version, self.e_entry,
             self.e_phoff, self.e_shoff, self.e_flags, self.e_ehsize,
             self.e_phentsize, self.e_phnum, self.e_shentsize,
             self.e_shnum, self.e_shstrndx) = hdr
        else:
            hdr = struct.unpack_from(endian + "HHIIIIIHHHHHH", data, 16)
            (self.e_type, self.e_machine, self.e_version, self.e_entry,
             self.e_phoff, self.e_shoff, self.e_flags, self.e_ehsize,
             self.e_phentsize, self.e_phnum, self.e_shentsize,
             self.e_shnum, self.e_shstrndx) = hdr

    def dynamic_entries(self):
        """Yield (tag, value) from .dynamic, using program headers."""
        # Locate PT_DYNAMIC via program headers.
        endian = "<"
        phoff, phentsize, phnum = self.e_phoff, self.e_phentsize, self.e_phnum
        dyn_off = dyn_size = None
        for i in range(phnum):
            off = phoff + i * phentsize
            if self.is64:
                p_type, p_flags, p_offset, p_vaddr, p_paddr, p_filesz, p_memsz, p_align = \
                    struct.unpack_from(endian + "IIQQQQQQ", self.data, off)
            else:
                p_type, p_offset, p_vaddr, p_paddr, p_filesz, p_memsz, p_flags, p_align = \
                    struct.unpack_from(endian + "IIIIIIII", self.data, off)
            if p_type == 2:  # PT_DYNAMIC
                dyn_off, dyn_size = p_offset, p_filesz
                break
        if dyn_off is None:
            raise SystemExit("no PT_DYNAMIC")
        entries = []
        entsize = 16 if self.is64 else 8
        for i in range(dyn_size // entsize):
            off = dyn_off + i * entsize
            if self.is64:
                tag, val = struct.unpack_from(endian + "QQ", self.data, off)
            else:
                tag, val = struct.unpack_from(endian + "II", self.data, off)
            if tag == DT_NULL:
                break
            entries.append((tag, val))
        return entries

    def dynstr_offset(self):
        # Find .dynstr section (SH_TYPE == 3 / SHT_STRTAB linked from .dynamic)
        if self.e_shoff == 0 or self.e_shnum == 0:
            raise SystemExit("stripped ELF without section headers")
        endian = "<"
        entsize = self.e_shentsize
        shstrtab_off = None
        # read section headers into list
        sections = []
        for i in range(self.e_shnum):
            off = self.e_shoff + i * entsize
            if self.is64:
                (sh_name, sh_type, sh_flags, sh_addr, sh_offset, sh_size,
                 sh_link, sh_info, sh_addralign, sh_entsize) = struct.unpack_from(
                    endian + "IIQQQQIIQQ", self.data, off)
            else:
                (sh_name, sh_type, sh_flags, sh_addr, sh_offset, sh_size,
                 sh_link, sh_info, sh_addralign, sh_entsize) = struct.unpack_from(
                    endian + "IIIIIIIIII", self.data, off)
            sections.append((sh_name, sh_type, sh_flags, sh_addr, sh_offset,
                             sh_size, sh_link, sh_info, sh_addralign, sh_entsize))
        # section-name string table
        shstr = sections[self.e_shstrndx]
        shstr_off = shstr[4]  # sh_offset
        def sec_name(s):
            nm_off = shstr_off + s[0]
            end = self.data.find(b"\x00", nm_off)
            return bytes(self.data[nm_off:end]).decode("ascii", "replace")
        for s in sections:
            name = sec_name(s)
            if name == ".dynstr":
                return s[4], s[5]  # sh_offset, sh_size
        raise SystemExit(".dynstr not found")

    def patch_string(self, table_off, string_off, new, old_len):
        """Overwrite the C string at table_off+string_off with `new` (padded to old_len)."""
        new_padded = new.encode("ascii") + b"\x00" * (old_len - len(new))
        start = table_off + string_off
        self.data[start:start + old_len] = new_padded

    def to_bytes(self):
        return bytes(self.data)


def patch_proot(proot_bytes):
    elf = Elf(proot_bytes)
    dynstr_off, _ = elf.dynstr_offset()
    for tag, val in elf.dynamic_entries():
        if tag in (DT_RUNPATH, DT_RPATH):
            old = "/data/data/com.termux/files/usr/lib"
            cur = bytes(elf.data[dynstr_off + val:]).split(b"\x00", 1)[0]
            if cur == b"/data/data/com.termux/files/usr/lib":
                elf.patch_string(dynstr_off, val, "$ORIGIN", len(old))
            else:
                raise SystemExit(f"unexpected RUNPATH {cur!r}")
        elif tag == DT_NEEDED:
            cur = bytes(elf.data[dynstr_off + val:]).split(b"\x00", 1)[0]
            if cur == b"libtalloc.so.2":
                elf.patch_string(dynstr_off, val, "libtalloc.so", len(b"libtalloc.so.2"))
    return elf.to_bytes()


def patch_talloc(talloc_bytes):
    elf = Elf(talloc_bytes)
    dynstr_off, _ = elf.dynstr_offset()
    for tag, val in elf.dynamic_entries():
        if tag == DT_SONAME:
            cur = bytes(elf.data[dynstr_off + val:]).split(b"\x00", 1)[0]
            if cur == b"libtalloc.so.2":
                elf.patch_string(dynstr_off, val, "libtalloc.so", len(b"libtalloc.so.2"))
    return elf.to_bytes()


def process_abi(android_abi, termux_arch):
    workdir = os.path.join(REPO, ".fetch-cache", termux_arch)
    os.makedirs(workdir, exist_ok=True)

    proot_deb = os.path.join(workdir, f"proot_{PROOT_VERSION}_{termux_arch}.deb")
    talloc_deb = os.path.join(workdir, f"libtalloc_{TALLOC_VERSION}_{termux_arch}.deb")
    shmem_deb = os.path.join(workdir, f"libandroid-shmem_{SHMEM_VERSION}_{termux_arch}.deb")

    download(f"{BASE}/p/proot/proot_{PROOT_VERSION}_{termux_arch}.deb", proot_deb)
    download(f"{BASE}/libt/libtalloc/libtalloc_{TALLOC_VERSION}_{termux_arch}.deb", talloc_deb)
    download(f"{BASE}/liba/libandroid-shmem/libandroid-shmem_{SHMEM_VERSION}_{termux_arch}.deb", shmem_deb)

    prefix = "data/data/com.termux/files/usr"
    proot_files = deb_files(proot_deb)
    talloc_files = deb_files(talloc_deb)
    shmem_files = deb_files(shmem_deb)

    proot_bin = proot_files[f"{prefix}/bin/proot"]
    loader = proot_files.get(f"{prefix}/libexec/proot/loader")
    loader32 = proot_files.get(f"{prefix}/libexec/proot/loader32")
    talloc_so = next(v for k, v in talloc_files.items() if "/libtalloc.so" in k)
    shmem_so = next(v for k, v in shmem_files.items() if "/libandroid-shmem.so" in k)

    out_dir = os.path.join(JNI, android_abi)
    os.makedirs(out_dir, exist_ok=True)

    def write(name, data):
        path = os.path.join(out_dir, name)
        with open(path, "wb") as f:
            f.write(data)
        print(f"  write {os.path.relpath(path, REPO)} ({len(data)} bytes)")

    write("libproot.so", patch_proot(proot_bin))
    if loader:
        write("libproot_loader.so", loader)
    if loader32:
        write("libproot_loader32.so", loader32)
    write("libtalloc.so", patch_talloc(talloc_so))
    write("libandroid-shmem.so", shmem_so)


def main():
    for android_abi, termux_arch in ABIS:
        print(f"[{android_abi} <- {termux_arch}]")
        process_abi(android_abi, termux_arch)
    print("done")


if __name__ == "__main__":
    main()
