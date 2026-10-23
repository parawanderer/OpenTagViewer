//! The same runtime as `vm.rs`, on icicle (MIT OR Apache-2.0) instead of Unicorn (GPL-2.0).
//!
//! The macOS userland (`Env`) is `vm.rs`'s, copied verbatim into `vm_icicle_env.rs`; only the CPU
//! underneath differs. Unicorn's code hook on the stub page becomes a breakpoint per handled stub:
//! run the handler, set `rax`, and perform the stub's `ret` by hand.
//!
//! `vm_icicle_env.rs` is not in this repository: `run.sh` generates it from os-findmy-e's `vm.rs`
//! at the pinned commit, so the userland can never drift from the one it is compared against.
//! `Vm` below follows os-findmy-e's `Vm` (MIT, Copyright (c) 2026 sacca) line for line where it can.

include!("vm_icicle_env.rs");

mod shim {
    use icicle_vm::cpu::mem::perm;
    use pcode::VarNode;

    #[derive(Clone, Copy)]
    pub enum RegisterX86 {
        RAX,
        RBP,
        RSP,
        RIP,
        RDI,
        RSI,
        RDX,
        RCX,
        R8,
        R9,
    }

    pub struct Cpu {
        vm: std::cell::UnsafeCell<icicle_vm::Vm>,
        regs: [VarNode; 10],
    }

    impl Cpu {
        pub fn new() -> Result<Cpu, String> {
            let config = icicle_vm::cpu::Config {
                enable_jit: std::env::var("ICICLE_NO_JIT").map_or(true, |v| v.is_empty()),
                enable_shadow_stack: false,
                ..icicle_vm::cpu::Config::from_target_triple("x86_64-none")
            };
            let vm = icicle_vm::build(&config).map_err(|e| format!("icicle build: {e:?}"))?;
            let names = ["RAX", "RBP", "RSP", "RIP", "RDI", "RSI", "RDX", "RCX", "R8", "R9"];
            let regs = names.map(|n| vm.cpu.arch.sleigh.get_varnode(n).expect(n));
            Ok(Cpu { vm: std::cell::UnsafeCell::new(vm), regs })
        }

        /// icicle reads through `&mut` (it caches translations); nothing here holds two at once.
        #[allow(clippy::mut_from_ref)]
        pub fn vm(&self) -> &mut icicle_vm::Vm {
            unsafe { &mut *self.vm.get() }
        }

        pub fn reg_read(&self, r: RegisterX86) -> Result<u64, ()> {
            if let RegisterX86::RIP = r {
                return Ok(self.vm().cpu.read_pc());
            }
            Ok(self.vm().cpu.read_reg(self.regs[r as usize]))
        }

        pub fn reg_write(&mut self, r: RegisterX86, v: u64) -> Result<(), String> {
            if let RegisterX86::RIP = r {
                self.vm().cpu.write_pc(v);
            } else {
                self.vm().cpu.write_reg(self.regs[r as usize], v);
            }
            Ok(())
        }

        pub fn mem_write(&mut self, addr: u64, data: &[u8]) -> Result<(), String> {
            self.vm().cpu.mem.write_bytes(addr, data, perm::NONE).map_err(|e| format!("write {addr:#x}: {e:?}"))
        }

        pub fn mem_read_as_vec(&self, addr: u64, len: usize) -> Result<Vec<u8>, String> {
            let mut buf = vec![0u8; len];
            self.vm().cpu.mem.read_bytes(addr, &mut buf, perm::NONE)
                .map_err(|e| format!("read {addr:#x}: {e:?}"))?;
            Ok(buf)
        }

        pub fn mem_map(&mut self, addr: u64, len: u64) -> Result<(), String> {
            let m = icicle_vm::cpu::mem::Mapping { perm: perm::READ | perm::WRITE | perm::EXEC, value: 0 };
            if self.vm().cpu.mem.map_memory_len(addr, len, m) { Ok(()) } else { Err(format!("map {addr:#x}")) }
        }

        pub fn mem_unmap(&mut self, addr: u64, len: u64) -> Result<(), String> {
            if self.vm().cpu.mem.unmap_memory_len(addr, len) { Ok(()) } else { Err(format!("unmap {addr:#x}")) }
        }

        /// Handlers call this in Unicorn to stop; here the run loop checks `Env::fault` instead.
        pub fn emu_stop(&mut self) -> Result<(), ()> {
            Ok(())
        }
    }
}

fn e(m: String) -> Error {
    Error::Emulation(m)
}

/// The pristine state right after load and binding, before any call into the library.
struct Snapshot {
    segments: Vec<(u64, Vec<u8>)>,
    cells: Vec<u8>,
    heap_cur: u64,
    obj_cur: u64,
    next_url: u64,
    next_fd: u64,
}

pub struct Vm {
    uc: Uc,
    env: Env,
    snapshot: Snapshot,
    zeros: Vec<u8>,
}

impl Vm {
    pub fn new(image: &[u8]) -> Result<Vm, Error> {
        let macho = MachO::parse(image)?;
        let mut uc = Uc::new().map_err(e)?;
        let mut env = Env::default();

        for (a, s) in [
            (RETURN_ADDRESS, PAGE),
            (HEAP_ADDRESS, HEAP_SIZE),
            (STACK_ADDRESS, STACK_SIZE),
            (STUB_ADDRESS, STUB_SIZE),
            (DATA_ADDRESS, DATA_SIZE),
            (SCRATCH_ADDRESS, SCRATCH_SIZE),
            (OBJECT_ADDRESS, OBJECT_SIZE),
        ] {
            uc.mem_map(a, s).map_err(e)?;
        }
        for seg in &macho.segments {
            let size = (seg.vmsize + PAGE - 1) / PAGE * PAGE;
            if size == 0 {
                continue;
            }
            uc.mem_map(seg.vmaddr, size).map_err(e)?;
            if seg.filesize != 0 {
                let blob = image
                    .get(seg.fileoff as usize..(seg.fileoff + seg.filesize) as usize)
                    .ok_or_else(|| Error::MachO("segment outside file".into()))?;
                uc.mem_write(seg.vmaddr, blob).map_err(e)?;
            }
        }
        uc.mem_write(STUB_ADDRESS, &vec![0xC3u8; STUB_SIZE as usize]).map_err(e)?;

        let mut stub_addr: HashMap<&str, u64> = HashMap::new();
        let mut data_cell: HashMap<&str, u64> = HashMap::new();
        let mut handlers = HashMap::new();
        let (mut object_of, mut key_by_object) = (HashMap::new(), HashMap::new());
        for (i, sym) in macho.bind_symbols().into_iter().enumerate() {
            let i = i as u64;
            if DATA_SYMBOLS.contains(&norm(sym)) {
                let (cell, obj) = (DATA_ADDRESS + i * SLOT, OBJECT_ADDRESS + i * SLOT);
                uc.mem_write(cell, &obj.to_le_bytes()).map_err(e)?;
                data_cell.insert(sym, cell);
                object_of.insert(norm(sym).to_string(), obj);
                key_by_object.insert(obj, sym.to_string());
            } else {
                let addr = STUB_ADDRESS + i * SLOT;
                stub_addr.insert(sym, addr);
                if let Some(h) = handler_for(norm(sym)) {
                    handlers.insert(addr, h);
                }
            }
        }
        for b in macho.binds() {
            let target = data_cell.get(b.symbol.as_str()).or_else(|| stub_addr.get(b.symbol.as_str()));
            let target = *target.ok_or_else(|| Error::MachO(format!("unbound symbol {}", b.symbol)))?;
            uc.mem_write(b.address, &target.wrapping_add(b.addend as u64).to_le_bytes()).map_err(e)?;
        }

        for addr in handlers.keys() {
            uc.vm().add_breakpoint(*addr);
        }
        uc.vm().add_breakpoint(RETURN_ADDRESS);

        env.handlers = handlers;
        env.object_of = object_of;
        env.key_by_object = key_by_object;
        env.heap_cur = HEAP_ADDRESS;
        env.scratch_cur = SCRATCH_ADDRESS;
        env.obj_cur = OBJECT_ADDRESS;
        env.next_url = OBJECT_ADDRESS;
        env.next_fd = 3;
        env.host_uuid = [
            0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF,
        ];
        env.dirs = ["/", "/Users", "/Users/Shared", STORE_DIR].iter().map(|s| s.to_string()).collect();

        env.errno_addr = env.bump_scratch(8);
        let mut cells = Vec::new();
        for (k, v) in [("HOME", "/Users/aditest"), ("USER", "aditest"), ("TMPDIR", "/tmp/")] {
            let a = env.bump_scratch(v.len() as u64 + 1);
            env.getenv_addr.insert(k, a);
            cells.push((a, v));
        }
        env.scratch_base = env.scratch_cur;
        for (a, v) in cells {
            uc.mem_write(a, format!("{v}\0").as_bytes()).map_err(e)?;
        }

        let mut segments = Vec::new();
        for seg in macho.segments.iter().filter(|s| s.writable && s.vmsize != 0) {
            segments.push((seg.vmaddr, uc.mem_read_as_vec(seg.vmaddr, seg.vmsize as usize).map_err(e)?));
        }
        let cells = uc.mem_read_as_vec(DATA_ADDRESS, PAGE as usize).map_err(e)?;
        let snapshot = Snapshot {
            segments,
            cells,
            heap_cur: env.heap_cur,
            obj_cur: env.obj_cur,
            next_url: env.next_url,
            next_fd: env.next_fd,
        };
        Ok(Vm { uc, env, snapshot, zeros: vec![0u8; STACK_SIZE as usize] })
    }

    /// Run `address(args...)` until it returns; the result is `rax`.
    pub fn invoke(&mut self, address: u64, args: &[u64]) -> Result<u64, Error> {
        use icicle_vm::VmExit;
        let uc = &mut self.uc;
        for (i, v) in args.iter().enumerate().take(6) {
            uc.reg_write(reg(i), *v).map_err(e)?;
        }
        let rsp = STACK_ADDRESS + STACK_SIZE - 0x1000;
        w64(uc, rsp, RETURN_ADDRESS);
        uc.reg_write(RegisterX86::RSP, rsp).map_err(e)?;
        uc.reg_write(RegisterX86::RBP, 0).map_err(e)?;
        uc.reg_write(RegisterX86::RIP, address).map_err(e)?;
        self.env.fault = None;
        loop {
            let exit = uc.vm().run();
            let pc = uc.vm().cpu.read_pc();
            match exit {
                VmExit::Breakpoint if pc == RETURN_ADDRESS => {
                    return Ok(uc.reg_read(RegisterX86::RAX).unwrap_or(0));
                }
                VmExit::Breakpoint => {
                    let Some(h) = self.env.handlers.get(&pc).copied() else {
                        return Err(Error::Emulation(format!("breakpoint at {pc:#x} with no handler")));
                    };
                    let r = self.env.call(uc, h);
                    if let Some(msg) = self.env.fault.take() {
                        return Err(Error::Emulation(msg));
                    }
                    if let Some(v) = r {
                        uc.reg_write(RegisterX86::RAX, v).map_err(e)?;
                    }
                    // The stub's `ret`.
                    let sp = uc.reg_read(RegisterX86::RSP).unwrap_or(0);
                    let ret = r64(uc, sp);
                    uc.reg_write(RegisterX86::RSP, sp + 8).map_err(e)?;
                    uc.reg_write(RegisterX86::RIP, ret).map_err(e)?;
                }
                other => {
                    return Err(Error::Emulation(format!("emulation failed at rip={pc:#x}: {other:?}")));
                }
            }
        }
    }

    pub fn reset_session(&mut self) -> Result<(), Error> {
        let uc = &mut self.uc;
        for (addr, bytes) in &self.snapshot.segments {
            uc.mem_write(*addr, bytes).map_err(e)?;
        }
        uc.mem_write(DATA_ADDRESS, &self.snapshot.cells).map_err(e)?;
        uc.mem_unmap(HEAP_ADDRESS, HEAP_SIZE).map_err(e)?;
        uc.mem_map(HEAP_ADDRESS, HEAP_SIZE).map_err(e)?;
        uc.mem_write(STACK_ADDRESS, &self.zeros).map_err(e)?;
        let env = &mut self.env;
        let (base, dirty) = (env.scratch_base, (env.scratch_cur - env.scratch_base) as usize);
        env.heap_cur = self.snapshot.heap_cur;
        env.obj_cur = self.snapshot.obj_cur;
        env.next_url = self.snapshot.next_url;
        env.next_fd = self.snapshot.next_fd;
        env.scratch_cur = env.scratch_base;
        env.fds.clear();
        env.urls.clear();
        env.fault = None;
        let errno = env.errno_addr;
        uc.mem_write(errno, &[0u8; 8]).map_err(e)?;
        uc.mem_write(base, &self.zeros[..dirty.min(self.zeros.len())]).map_err(e)?;
        Ok(())
    }

    pub fn scratch(&mut self, size: usize) -> u64 {
        self.env.bump_scratch(size as u64)
    }

    pub fn scratch_reset(&mut self) {
        self.env.scratch_cur = self.env.scratch_base;
    }

    pub fn write(&mut self, addr: u64, data: &[u8]) -> Result<(), Error> {
        self.uc.mem_write(addr, data).map_err(e)
    }

    pub fn read(&self, addr: u64, len: usize) -> Result<Vec<u8>, Error> {
        self.uc.mem_read_as_vec(addr, len).map_err(e)
    }

    pub fn read_u32(&self, addr: u64) -> Result<u32, Error> {
        Ok(u32::from_le_bytes(self.read(addr, 4)?.try_into().unwrap()))
    }

    pub fn read_u64(&self, addr: u64) -> Result<u64, Error> {
        Ok(r64(&self.uc, addr))
    }

    pub fn write_u64(&mut self, addr: u64, v: u64) -> Result<(), Error> {
        self.write(addr, &v.to_le_bytes())
    }

    pub fn write_u32(&mut self, addr: u64, v: u32) -> Result<(), Error> {
        self.write(addr, &v.to_le_bytes())
    }

    pub fn files(&self) -> &BTreeMap<String, Vec<u8>> {
        &self.env.files
    }

    pub fn files_mut(&mut self) -> &mut BTreeMap<String, Vec<u8>> {
        &mut self.env.files
    }

    pub fn set_host_uuid(&mut self, uuid: [u8; 16]) {
        self.env.host_uuid = uuid;
    }

    pub fn set_frozen_time(&mut self, secs: Option<u64>) {
        self.env.frozen_time = secs;
    }

    pub fn heap_used(&self) -> u64 {
        self.env.heap_cur - HEAP_ADDRESS
    }

    /// Guest instructions executed so far.
    pub fn icount(&self) -> u64 {
        self.uc.vm().cpu.icount()
    }
}
