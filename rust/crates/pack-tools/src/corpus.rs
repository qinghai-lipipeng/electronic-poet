//! `corpus` 子命令：从现代诗语料中按词性统计，构建词库包。
//!
//! 支持两种语料：
//! - sheepzh/poetry（华语现代诗歌语料库，MIT）的 `.pt` 文件：
//!   ```text
//!   title: 标题
//!   date: 日期
//!
//!   正文……
//!   ```
//! - 含 `paragraphs` 字符串数组的 JSON 文件。

use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};

use jieba_rs::Jieba;

use crate::parse_args;

/// 词性代码 → 应写入的词库类别。
fn codes_for_tag(tag: &str) -> &'static [&'static str] {
    match tag {
        // 名词性：人物 / 处所 / 方位 / 时间 / 机构 / 专名 / 简称
        "n" | "nr" | "ns" | "nt" | "nz" | "ng" | "s" | "f" | "t" | "j" => &["MM"],
        // 一般动词：自动、他动模板都可用
        "v" | "vg" => &["DD", "DJ"],
        // 名动词 / 副动词更偏他动
        "vd" | "vn" => &["DJ"],
        // 形容词 / 状态词 / 区别词
        "a" | "ad" | "an" | "ag" | "z" | "b" => &["XX"],
        // 叹词 / 拟声词（注意：不收 y 语气词——呢/吗/吧只适合句尾，
        // 放在模板句首的 TT 位置会很突兀）
        "e" | "o" => &["TT"],
        _ => &[],
    }
}

/// TT 白名单：只保留真正能独立成叹的叹词与常见叠字拟声词。
fn is_good_interjection(word: &str) -> bool {
    const INTERJECTIONS: &[&str] = &[
        "啊", "哦", "噢", "喔", "唉", "哎", "咦", "呀", "哇", "哈", "嘿", "哼", "嗯", "嘘",
        "嘻", "诶", "吖", "嗬", "嚯", "喔唷", "啊呀", "哎呀", "哎哟", "天啊",
    ];
    if INTERJECTIONS.contains(&word) {
        return true;
    }
    // 叠字 / AABB 式拟声词（如 咕噜咕噜、哗哗、咚咚）。
    let chars: Vec<char> = word.chars().collect();
    if chars.len() >= 2 {
        let repeated = chars.chunks(2).all(|c| c.len() == 2 && c[0] == c[1]);
        let abab = chars.len() == 4 && chars[0] == chars[2] && chars[1] == chars[3];
        return repeated || abab;
    }
    false
}

fn is_han_word(s: &str) -> bool {
    !s.is_empty()
        && s.chars()
            .all(|c| ('\u{4E00}'..='\u{9FFF}').contains(&c))
}

/// 递归收集 `.pt` 与 `.json` 文件。
fn walk_files(dir: &Path, out: &mut Vec<PathBuf>) {
    let Ok(entries) = fs::read_dir(dir) else {
        return;
    };
    for entry in entries.filter_map(|e| e.ok()) {
        let path = entry.path();
        if path.is_dir() {
            walk_files(&path, out);
        } else {
            let ext = path.extension().and_then(|x| x.to_str());
            if ext == Some("pt") || ext == Some("json") {
                out.push(path);
            }
        }
    }
}

/// 解析 `.pt` 文件，返回正文行。
fn parse_pt(content: &str) -> Vec<String> {
    let mut header_done = false;
    let mut out = Vec::new();
    for line in content.lines() {
        if !header_done {
            if line.trim().is_empty() {
                header_done = true;
            }
            // 头部行（title:/date:…）跳过。
            continue;
        }
        out.push(line.to_string());
    }
    if !header_done {
        // 没有空行分隔：把不含冒号的行视为正文。
        out = content
            .lines()
            .filter(|l| !l.contains(':') && !l.trim().is_empty())
            .map(String::from)
            .collect();
    }
    out
}

fn copy_dir_all(src: &Path, dst: &Path) -> Result<(), String> {
    fs::create_dir_all(dst).map_err(|e| e.to_string())?;
    for entry in fs::read_dir(src).map_err(|e| e.to_string())? {
        let entry = entry.map_err(|e| e.to_string())?;
        let from = entry.path();
        let to = dst.join(entry.file_name());
        if from.is_dir() {
            copy_dir_all(&from, &to)?;
        } else {
            fs::copy(&from, &to).map_err(|e| e.to_string())?;
        }
    }
    Ok(())
}

pub fn run(args: &[String]) -> Result<(), String> {
    let (_positional, named) = parse_args(args);
    let corpus_dir = named.get("corpus").ok_or("缺少参数：--corpus <语料目录>")?;
    let out_dir = named.get("out").ok_or("缺少参数：--out <输出包目录>")?;
    let top: usize = named.get("top").map(|x| x.parse().unwrap_or(2000)).unwrap_or(2000);
    let min_len: usize = named
        .get("min-len")
        .map(|x| x.parse().unwrap_or(2))
        .unwrap_or(2);

    let corpus_dir = PathBuf::from(corpus_dir);
    let out_dir = PathBuf::from(out_dir);

    // code -> (word -> count)
    let mut freq: HashMap<String, HashMap<String, u32>> = HashMap::new();
    let mut files = Vec::new();
    walk_files(&corpus_dir, &mut files);
    if files.is_empty() {
        return Err("语料目录中没有任何 .pt / .json 文件".to_string());
    }

    let jieba = Jieba::new();
    let mut poem_count = 0usize;
    let mut line_count = 0usize;

    for path in &files {
        let data = match fs::read(path) {
            Ok(d) => d,
            Err(_) => continue,
        };
        let is_pt = path.extension().and_then(|x| x.to_str()) == Some("pt");

        let mut texts: Vec<String> = Vec::new();
        if is_pt {
            let content = String::from_utf8_lossy(&data);
            texts = parse_pt(&content);
            poem_count += 1;
        } else if let Ok(value) = serde_json::from_slice::<serde_json::Value>(&data) {
            poem_count += 1;
            if let Some(arr) = value.get("paragraphs").and_then(|v| v.as_array()) {
                for item in arr {
                    if let Some(s) = item.as_str() {
                        texts.push(s.to_string());
                    }
                }
            }
            for key in ["content", "lines"] {
                if let Some(arr) = value.get(key).and_then(|v| v.as_array()) {
                    for item in arr {
                        if let Some(s) = item.as_str() {
                            texts.push(s.to_string());
                        }
                    }
                }
            }
        } else {
            continue;
        }

        for text in &texts {
            if text.trim().is_empty() {
                continue;
            }
            line_count += 1;
            for tag in jieba.tag(text, true) {
                let word = tag.word.trim();
                let len = word.chars().count();
                let target_codes = codes_for_tag(tag.tag);
                let is_tt = target_codes == &["TT"];
                if !is_han_word(word) || len > 6 || (!is_tt && len < min_len) {
                    continue;
                }
                for code in target_codes {
                    if *code == "TT" && !is_good_interjection(word) {
                        continue;
                    }
                    let bucket = freq.entry((*code).to_string()).or_default();
                    *bucket.entry(word.to_string()).or_insert(0) += 1;
                }
            }
        }
    }

    // 写出词库。
    let lex_dir = out_dir.join("lexicon");
    fs::create_dir_all(&lex_dir).map_err(|e| e.to_string())?;
    let mut summary: Vec<(String, usize)> = Vec::new();
    for (code, words) in &freq {
        let mut list: Vec<(&String, u32)> = words.iter().map(|(w, c)| (w, *c)).collect();
        list.sort_by(|a, b| b.1.cmp(&a.1).then_with(|| a.0.cmp(b.0)));
        list.truncate(top);
        let mut body = String::new();
        for (word, count) in &list {
            body.push_str(&format!("{word}\t{count}\n"));
        }
        fs::write(lex_dir.join(format!("{code}.txt")), body).map_err(|e| e.to_string())?;
        summary.push((code.clone(), list.len()));
    }
    summary.sort();

    // manifest.conf
    let pack_id = named.get("id").cloned().unwrap_or_else(|| "modern".to_string());
    let pack_name = named
        .get("name")
        .cloned()
        .unwrap_or_else(|| "现代诗语料包".to_string());
    let author = named.get("author").cloned().unwrap_or_default();
    let description = named
        .get("description")
        .cloned()
        .unwrap_or_else(|| "由现代诗语料自动统计生成".to_string());
    let manifest = format!(
        "id = {pack_id}\nname = {pack_name}\nversion = 1.0\n\
         author = {author}\ndescription = {description}\n"
    );
    fs::write(out_dir.join("manifest.conf"), manifest).map_err(|e| e.to_string())?;

    // 可选：复制语法模板。
    if let Some(gf) = named.get("grammar-from") {
        copy_dir_all(Path::new(gf), &out_dir.join("grammar"))?;
    }

    println!(
        "已处理 {poem_count} 个文件、{line_count} 行文本，词库包输出到：{}",
        out_dir.display()
    );
    for (code, n) in summary {
        println!("  {code}: {n} 词");
    }
    if !named.contains_key("grammar-from") {
        println!("提示：未提供 --grammar-from，请另行挂载 grammar/ 模板目录后该包才可用于生成。");
    }
    Ok(())
}
