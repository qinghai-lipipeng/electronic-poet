//! 可挂载词库包（语言资源包）的解析。
//!
//! 包目录结构（全部为 UTF-8 纯文本，便于随时手工编辑或整体替换）：
//!
//! ```text
//! <pack>/
//! ├── manifest.conf        # 元信息：key = value
//! ├── grammar/
//! │   ├── <name>.txt       # 正文句式模板，每行一个；可存在多个文件
//! │   └── title.txt        # （可选）标题模板
//! ├── lexicon/
//! │   └── <CODE>.txt       # 词库，CODE 为两个大写字母，如 MM.txt，每行一个词
//! └── rhyme/
//!     └── <RHYME>.txt      # （可选）韵部，如 ang.txt，每行 `CODE=词`
//! ```
//!
//! 模板中连续两个 ASCII 大写字母构成占位符，例如 `MM在DD`。
//! 重复的词 / 模板行天然代表更高权重；也可用 `内容<TAB>权重` 显式指定。

use std::collections::HashMap;
use std::fmt;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

/// 模板中的一个片段。
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Token {
    /// 字面文本。
    Literal(String),
    /// 占位符，内部为两字母代码，如 `MM`。
    Slot(String),
}

/// 一条句式模板。
#[derive(Debug, Clone)]
pub struct Template {
    pub tokens: Vec<Token>,
    pub weight: u32,
    pub source: String,
}

impl Template {
    /// 模板最后一个 token 是否为占位符（押韵候选位置）。
    pub fn tail_slot(&self) -> Option<&str> {
        match self.tokens.last() {
            Some(Token::Slot(code)) => Some(code.as_str()),
            _ => None,
        }
    }

    /// 模板引用到的全部占位符代码。
    pub fn slot_codes(&self) -> impl Iterator<Item = &str> {
        self.tokens.iter().filter_map(|t| match t {
            Token::Slot(c) => Some(c.as_str()),
            _ => None,
        })
    }
}

/// 包元信息。
#[derive(Debug, Clone, Default)]
pub struct Manifest {
    pub id: String,
    pub name: String,
    pub version: String,
    pub author: String,
    pub description: String,
    pub extra: HashMap<String, String>,
}

/// 带权重的词表（重复词已合并权重）。
pub type WeightedVec = Vec<(String, u32)>;

/// 一个已加载的语言资源包。
#[derive(Debug)]
pub struct Pack {
    pub root: PathBuf,
    pub manifest: Manifest,
    pub templates: Vec<Template>,
    pub title_templates: Vec<Template>,
    /// code -> 带权重词表
    pub lexicon: HashMap<String, WeightedVec>,
    /// 韵部 id -> (code -> 词表)
    pub rhymes: HashMap<String, HashMap<String, Vec<String>>>,
    /// 流式生成会话状态（begin/next/end）。
    pub(crate) session: Arc<Mutex<Option<crate::generator::GenerateSession>>>,
}

/// 包摘要（用于列表展示，无需完整加载）。
#[derive(Debug, Clone, serde::Serialize)]
pub struct PackSummary {
    pub id: String,
    pub name: String,
    pub version: String,
    pub author: String,
    pub description: String,
    pub path: String,
    pub templates: usize,
    pub title_templates: usize,
    pub lexicon_words: usize,
    pub lexicon_codes: Vec<String>,
    pub rhymes: Vec<String>,
}

/// 引擎错误。
#[derive(Debug)]
pub enum PoetError {
    Io(String, std::io::Error),
    Parse(String),
    Empty(String),
    InvalidState(String),
}

impl fmt::Display for PoetError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            PoetError::Io(p, e) => write!(f, "读取 `{p}` 失败：{e}"),
            PoetError::Parse(m) => write!(f, "解析失败：{m}"),
            PoetError::Empty(m) => write!(f, "{m}"),
            PoetError::InvalidState(m) => write!(f, "状态错误：{m}"),
        }
    }
}

impl std::error::Error for PoetError {}

pub type Result<T> = std::result::Result<T, PoetError>;

/// 去掉行注释与首尾空白。注释以 `#` 开头（仅整行或行内未转义的 `#`，
/// 词库若需包含 `#`，可写成 `\#`）。
fn clean_line(line: &str) -> Option<String> {
    let mut s = line.trim();
    if s.is_empty() || s.starts_with('#') {
        return None;
    }
    if let Some(idx) = s.find(" # ") {
        s = &s[..idx];
    }
    let s = s.trim().replace("\\#", "#");
    if s.is_empty() { None } else { Some(s) }
}

/// 拆分 `内容<TAB>权重`；无权重时返回 (内容, 1)。
fn split_weight(s: &str) -> (String, u32) {
    if let Some((body, w)) = s.split_once('\t') {
        let w = w.trim().parse::<u32>().unwrap_or(1).max(1);
        (body.trim().to_string(), w)
    } else {
        (s.to_string(), 1)
    }
}

/// 解析模板文本为 token 序列：连续两个 ASCII 大写字母为占位符。
pub fn parse_template(text: &str) -> Vec<Token> {
    let mut tokens: Vec<Token> = Vec::new();
    let mut literal = String::new();
    let chars: Vec<char> = text.chars().collect();
    let mut i = 0;
    while i < chars.len() {
        if chars[i].is_ascii_uppercase()
            && i + 1 < chars.len()
            && chars[i + 1].is_ascii_uppercase()
        {
            if !literal.is_empty() {
                tokens.push(Token::Literal(std::mem::take(&mut literal)));
            }
            tokens.push(Token::Slot(format!("{}{}", chars[i], chars[i + 1])));
            i += 2;
        } else {
            literal.push(chars[i]);
            i += 1;
        }
    }
    if !literal.is_empty() {
        tokens.push(Token::Literal(literal));
    }
    tokens
}

/// 解析 manifest.conf。
fn load_manifest(path: &Path) -> Result<Manifest> {
    let mut m = Manifest::default();
    if !path.exists() {
        return Ok(m);
    }
    let content =
        fs::read_to_string(path).map_err(|e| PoetError::Io(path.display().to_string(), e))?;
    for line in content.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let (k, v) = line
            .split_once('=')
            .ok_or_else(|| PoetError::Parse(format!("manifest 行缺少等号：{line}")))?;
        let k = k.trim();
        let v = v.trim().to_string();
        match k {
            "id" => m.id = v,
            "name" => m.name = v,
            "version" => m.version = v,
            "author" => m.author = v,
            "description" => m.description = v,
            other => {
                m.extra.insert(other.to_string(), v);
            }
        }
    }
    Ok(m)
}

/// 向带权重词表中累加一个词。
fn add_weighted(map: &mut WeightedVec, word: String, w: u32) {
    if let Some(slot) = map.iter_mut().find(|(x, _)| *x == word) {
        slot.1 = slot.1.saturating_add(w);
    } else {
        map.push((word, w));
    }
}

impl Pack {
    /// 从目录加载一个完整的语言资源包。
    pub fn load(root: impl AsRef<Path>) -> Result<Pack> {
        let root = root.as_ref().to_path_buf();
        if !root.is_dir() {
            return Err(PoetError::Empty(format!(
                "词库包目录不存在：{}",
                root.display()
            )));
        }
        let manifest = load_manifest(&root.join("manifest.conf"))?;

        // 语法模板：grammar/ 下除 title.txt 外全部 .txt
        let grammar_dir = root.join("grammar");
        let mut templates = Vec::new();
        let mut title_templates = Vec::new();
        if grammar_dir.exists() {
            let mut paths: Vec<PathBuf> = fs::read_dir(&grammar_dir)
                .map_err(|e| PoetError::Io(grammar_dir.display().to_string(), e))?
                .filter_map(|e| e.ok())
                .map(|e| e.path())
                .filter(|p| p.extension().and_then(|x| x.to_str()) == Some("txt"))
                .collect();
            paths.sort();
            for path in &paths {
                let is_title = path.file_stem().and_then(|x| x.to_str()) == Some("title");
                let content = fs::read_to_string(path)
                    .map_err(|e| PoetError::Io(path.display().to_string(), e))?;
                for line in content.lines() {
                    if let Some(body) = clean_line(line) {
                        let (text, w) = split_weight(&body);
                        let tpl = Template {
                            tokens: parse_template(&text),
                            weight: w.max(1),
                            source: path
                                .file_name()
                                .and_then(|x| x.to_str())
                                .unwrap_or("?")
                                .to_string(),
                        };
                        if is_title {
                            &mut title_templates
                        } else {
                            &mut templates
                        }
                        .push(tpl);
                    }
                }
            }
        }

        // 词库：lexicon/<CODE>.txt
        let mut lexicon: HashMap<String, WeightedVec> = HashMap::new();
        let lex_dir = root.join("lexicon");
        if lex_dir.exists() {
            let mut entries: Vec<PathBuf> = fs::read_dir(&lex_dir)
                .map_err(|e| PoetError::Io(lex_dir.display().to_string(), e))?
                .filter_map(|e| e.ok())
                .map(|e| e.path())
                .filter(|p| p.extension().and_then(|x| x.to_str()) == Some("txt"))
                .collect();
            entries.sort();
            for path in entries {
                let code = path
                    .file_stem()
                    .and_then(|x| x.to_str())
                    .unwrap_or_default()
                    .trim()
                    .to_uppercase();
                if code.len() != 2 || !code.chars().all(|c| c.is_ascii_uppercase()) {
                    return Err(PoetError::Parse(format!(
                        "词库文件名必须是两个大写字母：{}",
                        path.display()
                    )));
                }
                let content = fs::read_to_string(&path)
                    .map_err(|e| PoetError::Io(path.display().to_string(), e))?;
                let bucket = lexicon.entry(code).or_default();
                for line in content.lines() {
                    if let Some(body) = clean_line(line) {
                        let (word, w) = split_weight(&body);
                        add_weighted(bucket, word, w);
                    }
                }
            }
        }

        // 韵部：rhyme/<ID>.txt，每行 CODE=词
        let mut rhymes: HashMap<String, HashMap<String, Vec<String>>> = HashMap::new();
        let rhyme_dir = root.join("rhyme");
        if rhyme_dir.exists() {
            let mut entries: Vec<PathBuf> = fs::read_dir(&rhyme_dir)
                .map_err(|e| PoetError::Io(rhyme_dir.display().to_string(), e))?
                .filter_map(|e| e.ok())
                .map(|e| e.path())
                .filter(|p| p.extension().and_then(|x| x.to_str()) == Some("txt"))
                .collect();
            entries.sort();
            for path in entries {
                let rid = path
                    .file_stem()
                    .and_then(|x| x.to_str())
                    .unwrap_or_default()
                    .trim()
                    .to_lowercase();
                let content = fs::read_to_string(&path)
                    .map_err(|e| PoetError::Io(path.display().to_string(), e))?;
                let bucket = rhymes.entry(rid).or_default();
                for line in content.lines() {
                    if let Some(body) = clean_line(line) {
                        let (code, word) = body.split_once('=').ok_or_else(|| {
                            PoetError::Parse(format!(
                                "韵部文件 {} 中每行应为 `CODE=词`：{body}",
                                path.display()
                            ))
                        })?;
                        let code = code.trim().to_uppercase();
                        let word = word.trim().to_string();
                        let list = bucket.entry(code).or_default();
                        if !list.contains(&word) {
                            list.push(word);
                        }
                    }
                }
            }
        }

        if templates.is_empty() {
            return Err(PoetError::Empty(format!(
                "词库包 `{}` 没有任何正文模板（grammar/*.txt）",
                root.display()
            )));
        }

        Ok(Pack {
            root,
            manifest,
            templates,
            title_templates,
            lexicon,
            rhymes,
            session: Arc::new(Mutex::new(None)),
        })
    }

    /// 某占位符词库的词数。
    pub fn word_count(&self, code: &str) -> usize {
        self.lexicon
            .get(code)
            .map(|v| v.len())
            .unwrap_or(0)
    }

    /// 判断模板在“普通（非押韵）”情形下是否可填充：所有占位符词库均非空。
    pub fn is_usable(&self, tpl: &Template) -> bool {
        tpl.slot_codes()
            .all(|c| self.lexicon.get(c).map(|v| !v.is_empty()).unwrap_or(false))
    }

    /// 判断模板在指定韵部下能否作为押韵行：句尾为占位符，
    /// 且韵库中该占位符有词，其余占位符词库非空。
    pub fn is_rhymable(&self, tpl: &Template, rhyme_id: &str) -> bool {
        let tail = match tpl.tail_slot() {
            Some(c) => c,
            None => return false,
        };
        let rhyme_ok = self
            .rhymes
            .get(rhyme_id)
            .and_then(|m| m.get(tail))
            .map(|v| !v.is_empty())
            .unwrap_or(false);
        if !rhyme_ok {
            return false;
        }
        tpl.slot_codes().all(|c| {
            if c == tail {
                true // 尾词由韵库提供
            } else {
                self.lexicon.get(c).map(|v| !v.is_empty()).unwrap_or(false)
            }
        })
    }

    /// 摘要。
    pub fn summary(&self) -> PackSummary {
        let mut lexicon_codes: Vec<String> = self.lexicon.keys().cloned().collect();
        lexicon_codes.sort();
        let mut rhymes: Vec<String> = self.rhymes.keys().cloned().collect();
        rhymes.sort();
        PackSummary {
            id: self.manifest.id.clone(),
            name: self.manifest.name.clone(),
            version: self.manifest.version.clone(),
            author: self.manifest.author.clone(),
            description: self.manifest.description.clone(),
            path: self.root.display().to_string(),
            templates: self.templates.len(),
            title_templates: self.title_templates.len(),
            lexicon_words: self.lexicon.values().map(|v| v.len()).sum(),
            lexicon_codes,
            rhymes,
        }
    }
}

/// 扫描挂载根目录，列出所有一级子目录中合法的词库包（含 manifest.conf）。
pub fn list_packs(root: impl AsRef<Path>) -> Vec<PackSummary> {
    let root = root.as_ref();
    let mut out = Vec::new();
    let entries = match fs::read_dir(root) {
        Ok(e) => e,
        Err(_) => return out,
    };
    let mut paths: Vec<PathBuf> = entries
        .filter_map(|e| e.ok())
        .map(|e| e.path())
        .filter(|p| p.is_dir())
        .collect();
    paths.sort();
    for p in paths {
        if p.join("manifest.conf").exists() {
            if let Ok(pack) = Pack::load(&p) {
                out.push(pack.summary());
            }
        }
    }
    out
}
