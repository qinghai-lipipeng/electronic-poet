//! pack-tools —— 词库包离线构建工具（在开发机运行，与 App 无关）。
//!
//! 子命令：
//! - `rhyme  <pack_dir> [--min N]`
//!   读取 pack 的 lexicon，按末字拼音归入汉语“十三辙”，生成 rhyme/*.txt。
//! - `corpus --corpus <dir> --out <pack_dir> [--top N] [--grammar-from <dir>]
//!   用 jieba-rs 对现代诗语料分词并按词性统计，生成 lexicon 与 manifest。

mod corpus;
mod rhyme;

fn print_help() {
    println!(
        "pack-tools：词库包离线构建工具\n\n\
         USAGE:\n  \
         pack-tools rhyme  <pack_dir> [--min N]\n  \
         pack-tools corpus --corpus <语料目录> --out <输出包目录> [--top N]\n  \
         (corpus 可选：--grammar-from <模板目录> --name <名称> --id <id> --author <作者> --description <描述>)\n"
    );
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let (cmd, rest) = match args.split_first() {
        Some((c, r)) => (c.as_str(), r),
        None => {
            print_help();
            return;
        }
    };
    let result = match cmd {
        "rhyme" => rhyme::run(rest),
        "corpus" => corpus::run(rest),
        other => {
            eprintln!("未知子命令：{other}");
            print_help();
            std::process::exit(2);
        }
    };
    if let Err(e) = result {
        eprintln!("错误：{e}");
        std::process::exit(1);
    }
}

/// 简单的 `--key value` / 位置参数解析。
pub fn parse_args(args: &[String]) -> (Vec<String>, std::collections::HashMap<String, String>) {
    let mut positional = Vec::new();
    let mut named = std::collections::HashMap::new();
    let mut i = 0;
    while i < args.len() {
        if let Some(key) = args[i].strip_prefix("--") {
            if i + 1 < args.len() {
                named.insert(key.to_string(), args[i + 1].clone());
                i += 2;
            } else {
                i += 1;
            }
        } else {
            positional.push(args[i].clone());
            i += 1;
        }
    }
    (positional, named)
}

/// 把末字的完整拼音（pinyin crate 的 plain 形式，如 `xiang`、`lü`）
/// 归一化为标准韵母串。
pub fn standard_final(p: &str) -> String {
    // 整体认读音节与 y / w 零声母特例。
    match p {
        "zhi" | "chi" | "shi" | "ri" | "zi" | "ci" | "si" | "yi" => return "i".into(),
        "yin" => return "in".into(),
        "ying" => return "ing".into(),
        "wu" => return "u".into(),
        "yu" => return "ü".into(),
        "yun" => return "ün".into(),
        "ye" => return "ie".into(),
        "yue" => return "üe".into(),
        "yuan" => return "üan".into(),
        "ya" | "wa" => return "a".into(),
        "yao" => return "ao".into(),
        "you" => return "ou".into(),
        "yan" | "wan" => return "an".into(),
        "yang" | "wang" => return "ang".into(),
        "yong" => return "iong".into(),
        "wo" => return "o".into(),
        "wai" => return "ai".into(),
        "wei" => return "ei".into(),
        "wen" => return "en".into(),
        "weng" => return "ueng".into(),
        _ => {}
    }
    // j / q / x 后的 u 系列实为 ü 系列。
    for pre in ["j", "q", "x"] {
        if let Some(rest) = p.strip_prefix(pre) {
            let r = match rest {
                "u" => "ü",
                "ue" => "üe",
                "uan" => "üan",
                "un" => "ün",
                _ => rest,
            };
            return r.into();
        }
    }
    // 普通音节剥声母（zh/ch/sh 优先）。
    const INITIALS: &[&str] = &[
        "zh", "ch", "sh", "b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h", "r", "z", "c",
        "s",
    ];
    for ini in INITIALS {
        if let Some(rest) = p.strip_prefix(ini) {
            return rest.into();
        }
    }
    p.into()
}

/// 标准韵母 → 十三辙 id。
pub fn final_to_zhe(fin: &str) -> Option<&'static str> {
    const RULES: &[(&str, &str)] = &[
        // 中东辙：eng / ing / ong / iong / ueng
        ("ong", "zhong"),
        ("iong", "zhong"),
        ("ueng", "zhong"),
        ("eng", "zhong"),
        ("ing", "zhong"),
        // 江阳辙：ang 系
        ("ang", "jiang"),
        ("iang", "jiang"),
        ("uang", "jiang"),
        // 人辰辙：en / in / un / ün
        ("en", "ren"),
        ("in", "ren"),
        ("un", "ren"),
        ("ün", "ren"),
        // 言前辙：an 系
        ("an", "yan"),
        ("ian", "yan"),
        ("uan", "yan"),
        ("üan", "yan"),
        // 由求辙：ou / iu
        ("ou", "you"),
        ("iu", "you"),
        // 遥条辙：ao / iao
        ("ao", "yao"),
        ("iao", "yao"),
        // 灰堆辙：ei / ui
        ("ei", "hui"),
        ("ui", "hui"),
        // 怀来辙：ai / uai
        ("ai", "huai"),
        ("uai", "huai"),
        // 乜斜辙：ie / üe
        ("ie", "mie"),
        ("üe", "mie"),
        // 发花辙：a / ia / ua
        ("a", "fa"),
        ("ia", "fa"),
        ("ua", "fa"),
        // 梭波辙：o / e / uo
        ("o", "suo"),
        ("e", "suo"),
        ("uo", "suo"),
        // 一七辙：i / ü / er
        ("i", "yi"),
        ("ü", "yi"),
        ("er", "yi"),
        // 姑苏辙：u
        ("u", "gu"),
    ];
    RULES.iter().find(|(f, _)| *f == fin).map(|(_, z)| *z)
}
