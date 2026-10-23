//! Offline differential run: the same CoreADI calls, deterministic inputs, no network.
//! Build once with and once without `--features icicle` and diff stdout.
use std::time::Instant;

use adi_core::CoreAdi;
use sha2::{Digest, Sha256};

fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

fn store_sha(adi: &mut CoreAdi) -> String {
    let mut h = Sha256::new();
    for (k, v) in adi.vm().files() {
        h.update(k.as_bytes());
        h.update((v.len() as u64).to_le_bytes());
        h.update(v);
    }
    hex(&h.finalize()[..8])
}

fn main() {
    std::thread::Builder::new().stack_size(512 << 20).spawn(run).unwrap().join().unwrap();
}

fn run() {
    let path = std::env::args().nth(1).expect("path to CoreADI");
    let image = std::fs::read(path).unwrap();
    let t = Instant::now();
    let mut adi = CoreAdi::new(&image).unwrap();
    eprintln!("load {:?}", t.elapsed());
    adi.set_host_uuid(*b"\x10\x32\x54\x76\x98\xba\xdc\xfe\x01\x23\x45\x67\x89\xab\xcd\xef");
    adi.set_frozen_time(Some(1_790_000_000));

    let t = Instant::now();
    println!("begin_session {:?}", adi.begin_session());
    println!("is_machine_provisioned {:?}", adi.is_machine_provisioned(-2));
    println!("request_otp(unprovisioned) {:?}", adi.request_otp(-2).map(|o| (o.reply, hex(&o.mid), hex(&o.otp))));
    println!("get_routing {:?}", adi.get_routing(-2));
    println!("store {}", store_sha(&mut adi));

    // ProvisioningStart parses Apple's spim and builds the client message from it. Without
    // Apple there is no valid spim, so feed several deterministic blobs and compare whatever the
    // library does with each, byte for byte.
    let mut seed = 0x9E37_79B9_7F4A_7C15u64;
    for (i, len) in [0usize, 16, 256, 1024, 4096].into_iter().enumerate() {
        let spim: Vec<u8> = (0..len)
            .map(|_| {
                seed ^= seed << 13;
                seed ^= seed >> 7;
                seed ^= seed << 17;
                seed as u8
            })
            .collect();
        println!("begin_session {:?}", adi.begin_session());
        match adi.provisioning_start(-2, &spim) {
            Ok(p) => println!(
                "provisioning_start[{i}] len={len} {:?} session={} cpim({})={}",
                p.reply,
                p.session,
                p.cpim.len(),
                hex(&Sha256::digest(&p.cpim)[..16])
            ),
            Err(e) => println!("provisioning_start[{i}] len={len} error {e}"),
        }
        println!("store {}", store_sha(&mut adi));
    }
    eprintln!("calls {:?}", t.elapsed());
    #[cfg(feature = "icicle")]
    eprintln!("guest instructions {}", adi.vm().icount());
}
