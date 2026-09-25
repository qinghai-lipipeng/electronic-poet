//! `rhyme` 子命令：为词库包生成十三辙韵部文件。

use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::path::PathBuf;

use pinyin::ToPinyin;

use crate::{final_to_zhe, parse_args, standard_final};

pub fn run(args: &[String]) -> Result<(), String> {
    let (positional, named) = parse_args(args);
    let pack_dir = positional
        .first()
        .ok_or("缺少参数：词库包目录")?;
    let pack_dir = PathBuf::from(pack_dir);
    let min: usize = named
        .get("min")
        .map(|x| x.parse().unwrap_or(3))
        .unwrap_or(3);

    let lex_dir = pack_dir.join("lexicon");
    if !lex_dir.is_dir() {
        return Err(format!("词库目录不存在：{}", lex_dir.display()));
    }

    // zhe -> code -> 词集合
    let mut grouped: BTreeMap<String, BTreeMap<String, BTreeSet<String>>> = BTreeMap::new();
    let mut skipped = 0usize;

    let mut files: Vec<PathBuf> = fs::read_dir(&lex_dir)
        .map_err(|e| e.to_string())?
        .filter_map(|e| e.ok())
        .map(|e| e.path())
        .filter(|p| p.extension().and_then(|x| x.to_str()) == Some("txt"))
        .collect();
    files.sort();

    for path in files {
        let code = path
            .file_stem()
            .and_then(|x| x.to_str())
            .unwrap_or_default()
            .to_uppercase();
        let content = fs::read_to_string(&path).map_err(|e| e.to_string())?;
        for line in content.lines() {
            let line = line.trim();
            if line.is_empty() || line.starts_with('#') {
                continue;
            }
            let word = line.split('\t').next().unwrap_or(line).trim();
            let Some(last) = word.chars().last() else {
                continue;
            };
            let Some(py) = last.to_pinyin() else {
                skipped += 1;
                continue;
            };
            let fin = standard_final(py.plain());
            let Some(zhe) = final_to_zhe(&fin) else {
                skipped += 1;
                continue;
            };
            grouped
                .entry(zhe.to_string())
                .or_default()
                .entry(code.clone())
                .or_default()
                .insert(word.to_string());
        }
    }

    // 重建 rhyme 目录，避免残留旧文件。
    let rhyme_dir = pack_dir.join("rhyme");
    if rhyme_dir.exists() {
        fs::remove_dir_all(&rhyme_dir).map_err(|e| e.to_string())?;
    }
    fs::create_dir_all(&rhyme_dir).map_err(|e| e.to_string())?;

    let mut written = 0usize;
    for (zhe, by_code) in &grouped {
        // 只保留词数达阈值的 code；整个辙若没有任何达标 code 则跳过。
        let lines: Vec<String> = by_code
            .iter()
            .filter(|(_, words)| words.len() >= min)
            .flat_map(|(code, words)| {
                words
                    .iter()
                    .map(move |w| format!("{code}={w}"))
            })
            .collect();
        if lines.is_empty() {
            continue;
        }
        let mut body = format!("# {zhe} 辙（由 pack-tools 自动生成，可手工编辑）\n");
        for l in lines {
            body.push_str(&l);
            body.push('\n');
        }
        fs::write(rhyme_dir.join(format!("{zhe}.txt")), body).map_err(|e| e.to_string())?;
        written += 1;
    }

    println!(
        "已在 {} 生成 {written} 个韵部文件（每类至少 {min} 词；跳过 {skipped} 个无法归韵的词）",
        rhyme_dir.display()
    );
    Ok(())
}
