//! 命令行试写：`cargo run -p poet-core --example poem -- <pack_dir> [seed]`

use poet_core::{GenOptions, Pack};

fn main() {
    let dir = std::env::args().nth(1).expect("需要词库包目录参数");
    let seed = std::env::args()
        .nth(2)
        .and_then(|s| s.parse::<u64>().ok());
    let pack = Pack::load(&dir).unwrap_or_else(|e| {
        eprintln!("{e}");
        std::process::exit(1);
    });

    let opts = GenOptions {
        paragraphs: 3,
        lines_per_paragraph: 4,
        rhyme: true,
        make_title: true,
        seed,
        ..Default::default()
    };
    let poem = pack.generate(&opts).unwrap();
    if let Some(t) = &poem.title {
        println!("《{t}》");
    }
    for stanza in &poem.paragraphs {
        for line in stanza {
            println!("{line}");
        }
        println!();
    }
    eprintln!("(pack={}, seed={}, rhymes={:?})", poem.pack_id, poem.seed, poem.rhymes);
    for w in &poem.warnings {
        eprintln!("警告：{w}");
    }
}
