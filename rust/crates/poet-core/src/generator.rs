//! 诗歌生成器：随机选取语法模板，再把可挂载词库中的词按占位符嵌入。
//!
//! 这是纯粹的“模板匹配 + 嵌词”过程，不含任何模型或学习成分。

use std::collections::HashSet;

use serde::{Deserialize, Serialize};

use crate::pack::{Pack, Template, Token, WeightedVec};

/// 流式生成会话：保存 begin → next → end 之间的状态。
#[derive(Debug)]
pub struct GenerateSession {
    pub(crate) rng: Rng,
    pub(crate) options: GenOptions,
    pub(crate) current_rhyme: Option<String>,
    /// 已使用过的韵部（HashSet，避免每段 push 导致内存随段落数线性增长）。
    pub(crate) used_rhymes: HashSet<String>,
    /// 缓存的可用韵部列表（begin 时计算一次，next 中直接复用）。
    pub(crate) available_rhymes: Vec<String>,
    pub(crate) current: usize,
    pub(crate) total: usize,
    pub(crate) title: Option<String>,
    pub(crate) warnings: Vec<String>,
}

/// 单段输出（流式 next_paragraph 返回）。
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ParagraphOut {
    pub lines: Vec<String>,
    pub rhyme: Option<String>,
}

/// 流式生成开始时的返回（begin_generate）。
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BeginOut {
    pub title: Option<String>,
    /// 本次实际使用的随机种子：未指定种子时由引擎随机生成，可用于复现同一首诗。
    pub seed: u64,
}

/// 押韵排布方式。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum RhymeScheme {
    /// 每行都押。
    Every,
    /// 隔行押（段内第 2、4、6… 行）。
    Alternate,
}

impl Default for RhymeScheme {
    fn default() -> Self {
        RhymeScheme::Every
    }
}

/// 生成参数（可由上层 JSON 直接反序列化，字段均带默认值）。
#[derive(Debug, Clone, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct GenOptions {
    /// 段落数。
    pub paragraphs: usize,
    /// 每段行数。
    pub lines_per_paragraph: usize,
    /// 是否押韵。
    pub rhyme: bool,
    /// 指定韵部 id；None 表示自动随机选取。
    pub rhyme_id: Option<String>,
    /// 每段是否换韵。
    pub per_paragraph_rhyme: bool,
    /// 押韵排布。
    pub rhyme_scheme: RhymeScheme,
    /// 是否生成标题。
    pub make_title: bool,
    /// 随机种子；None 表示由引擎随机生成，实际使用的种子会随生成结果返回。
    pub seed: Option<u64>,
}

impl Default for GenOptions {
    fn default() -> Self {
        GenOptions {
            paragraphs: 3,
            lines_per_paragraph: 4,
            rhyme: false,
            rhyme_id: None,
            per_paragraph_rhyme: true,
            rhyme_scheme: RhymeScheme::Every,
            make_title: true,
            seed: None,
        }
    }
}

/// 生成结果。
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PoemOut {
    pub title: Option<String>,
    /// 每段若干行。
    pub paragraphs: Vec<Vec<String>>,
    pub pack_id: String,
    pub pack_name: String,
    pub seed: u64,
    /// 每段实际使用的韵部（未押韵为 None）。
    pub rhymes: Vec<Option<String>>,
    pub warnings: Vec<String>,
}

/// SplitMix64 伪随机数发生器：确定、快速、零依赖，种子可复现。
#[derive(Debug, Clone)]
pub struct Rng {
    state: u64,
}

impl Rng {
    pub fn new(seed: u64) -> Self {
        Rng { state: seed }
    }

    pub fn next_u64(&mut self) -> u64 {
        self.state = self.state.wrapping_add(0x9E37_79B9_7F4A_7C15);
        let mut z = self.state;
        z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
        z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
        z ^ (z >> 31)
    }

    /// 返回 [0, n) 内的均匀整数。
    pub fn below(&mut self, n: usize) -> usize {
        if n == 0 {
            0
        } else {
            (self.next_u64() % n as u64) as usize
        }
    }

    /// 从带权重列表中等概率（按权重）抽取一个元素的引用。
    pub fn weighted<'a, T>(&mut self, items: &'a [(T, u32)]) -> &'a T {
        let total: u64 = items.iter().map(|(_, w)| *w as u64).sum();
        debug_assert!(total > 0);
        let mut roll = self.next_u64() % total;
        for (item, w) in items {
            let w = *w as u64;
            if roll < w {
                return item;
            }
            roll -= w;
        }
        &items.last().expect("non-empty").0
    }

    /// 从普通切片均匀抽取引用。
    pub fn pick<'a, T>(&mut self, items: &'a [T]) -> &'a T {
        &items[self.below(items.len())]
    }
}

impl Pack {
    /// 开始一次流式生成会话，返回标题（若开启）与本次实际使用的种子。
    /// 之后反复调用 [`Pack::next_paragraph`] 获取每一段，最后调用 [`Pack::end_generate`]。
    pub fn begin_generate(&self, options: &GenOptions) -> crate::pack::Result<BeginOut> {
        let seed = options.seed.unwrap_or_else(|| {
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_nanos() as u64)
                .unwrap_or(0xDEAD_BEEF)
        });
        let mut rng = Rng::new(seed);
        let mut warnings = Vec::new();

        let usable: Vec<&Template> = self
            .templates
            .iter()
            .filter(|t| self.is_usable(t))
            .collect();
        if usable.is_empty() {
            return Err(crate::pack::PoetError::Empty(
                "没有任何可填充的模板：模板引用的词库全部缺失或为空".to_string(),
            ));
        }

        let available_rhymes: Vec<String> = if options.rhyme {
            let mut v: Vec<String> = self
                .rhymes
                .keys()
                .filter(|rid| {
                    self.templates
                        .iter()
                        .any(|t| self.is_rhymable(t, rid.as_str()))
                })
                .cloned()
                .collect();
            v.sort();
            v
        } else {
            Vec::new()
        };
        if options.rhyme && available_rhymes.is_empty() {
            warnings.push("已开启押韵，但当前词库包没有可用韵部，本次不押韵".to_string());
        }

        let current_rhyme: Option<String> = if options.rhyme && !available_rhymes.is_empty() {
            match &options.rhyme_id {
                Some(rid) if available_rhymes.contains(rid) => Some(rid.clone()),
                Some(_) => {
                    warnings.push("指定韵部在当前词库包中不可用，已自动选取".to_string());
                    Some(rng.pick(&available_rhymes).clone())
                }
                None => Some(rng.pick(&available_rhymes).clone()),
            }
        } else {
            None
        };
        let mut used_rhymes: HashSet<String> = HashSet::new();
        if let Some(r) = &current_rhyme {
            used_rhymes.insert(r.clone());
        }

        let title = if options.make_title {
            let usable_titles: Vec<&Template> = self
                .title_templates
                .iter()
                .filter(|t| self.is_usable(t))
                .collect();
            if usable_titles.is_empty() {
                None
            } else {
                let weighted = to_weighted(&usable_titles);
                let tpl = rng.weighted(&weighted);
                Some(self.fill(&mut rng, tpl, None, false))
            }
        } else {
            None
        };

        let session = GenerateSession {
            rng,
            options: options.clone(),
            current_rhyme,
            used_rhymes,
            available_rhymes,
            current: 0,
            total: options.paragraphs.max(1),
            title: title.clone(),
            warnings,
        };
        *self.session.lock().unwrap() = Some(session);
        Ok(BeginOut { title, seed })
    }

    /// 生成下一段。返回 `None` 表示全部段落已生成完毕。
    pub fn next_paragraph(&self) -> crate::pack::Result<Option<ParagraphOut>> {
        let mut guard = self.session.lock().unwrap();
        let s = match guard.as_mut() {
            Some(s) => s,
            None => {
                return Err(crate::pack::PoetError::InvalidState(
                    "未调用 begin_generate".to_string(),
                ))
            }
        };
        if s.current >= s.total {
            return Ok(None);
        }

        let options = &s.options;
        let usable: Vec<&Template> = self
            .templates
            .iter()
            .filter(|t| self.is_usable(t))
            .collect();
        // 直接复用 begin 时缓存的可用韵部列表，不再每段重算。
        let available_rhymes = &s.available_rhymes;

        if s.current > 0
            && options.rhyme
            && options.per_paragraph_rhyme
            && !available_rhymes.is_empty()
        {
            let mut candidate = s.rng.pick(available_rhymes).clone();
            if available_rhymes.len() > s.used_rhymes.len() {
                let mut g = 0;
                while s.used_rhymes.contains(&candidate) && g < 20 {
                    candidate = s.rng.pick(available_rhymes).clone();
                    g += 1;
                }
            }
            s.used_rhymes.insert(candidate.clone());
            s.current_rhyme = Some(candidate);
        }

        let lines = options.lines_per_paragraph.max(1);
        let mut stanza: Vec<String> = Vec::with_capacity(lines);
        for line_idx in 0..lines {
            let is_rhyme_line = s.current_rhyme.is_some()
                && match options.rhyme_scheme {
                    RhymeScheme::Every => true,
                    RhymeScheme::Alternate => line_idx % 2 == 1,
                };

            let line = if is_rhyme_line {
                let rid = s.current_rhyme.as_deref().unwrap();
                let rhymable: Vec<&Template> = self
                    .templates
                    .iter()
                    .filter(|t| self.is_rhymable(t, rid))
                    .collect();
                if rhymable.is_empty() {
                    let weighted = to_weighted(&usable);
                    let tpl = s.rng.weighted(&weighted);
                    self.fill(&mut s.rng, tpl, None, false)
                } else {
                    let weighted = to_weighted(&rhymable);
                    let tpl = s.rng.weighted(&weighted);
                    self.fill(&mut s.rng, tpl, Some(rid), true)
                }
            } else {
                let weighted = to_weighted(&usable);
                let tpl = s.rng.weighted(&weighted);
                self.fill(&mut s.rng, tpl, None, false)
            };
            stanza.push(line);
        }

        let rhyme = s.current_rhyme.clone();
        s.current += 1;
        Ok(Some(ParagraphOut { lines: stanza, rhyme }))
    }

    /// 结束流式生成会话，返回累积的 warnings。
    pub fn end_generate(&self) -> Vec<String> {
        self.session
            .lock()
            .unwrap()
            .take()
            .map(|s| s.warnings)
            .unwrap_or_default()
    }

    /// 按参数生成一首现代诗（一次性，内部调用流式接口）。
    pub fn generate(&self, options: &GenOptions) -> crate::pack::Result<PoemOut> {
        let begin = self.begin_generate(options)?;
        let mut paragraphs: Vec<Vec<String>> = Vec::new();
        let mut rhymes: Vec<Option<String>> = Vec::new();
        while let Some(p) = self.next_paragraph()? {
            rhymes.push(p.rhyme);
            paragraphs.push(p.lines);
        }
        let warnings = self.end_generate();
        Ok(PoemOut {
            title: begin.title,
            paragraphs,
            pack_id: self.manifest.id.clone(),
            pack_name: self.manifest.name.clone(),
            seed: begin.seed,
            rhymes,
            warnings,
        })
    }

    /// 填充单个模板。
    /// - `rhyme_id` 存在且 `rhyme_line` 为真时，句尾占位符从韵库取词。
    fn fill(
        &self,
        rng: &mut Rng,
        tpl: &Template,
        rhyme_id: Option<&str>,
        rhyme_line: bool,
    ) -> String {
        // 押韵位置是模板的最后一个占位符（句尾）；句中出现同名占位符时，
        // 仍从词库取词，不能一并当作韵脚。
        let tail_idx = tpl
            .tokens
            .len()
            .checked_sub(1)
            .filter(|&i| matches!(tpl.tokens[i], Token::Slot(_)));
        // 记录本次填充中已用过的 (code, word)，降低同句重复。
        let mut used: HashSet<(String, String)> = HashSet::new();
        let mut out = String::new();

        for (idx, token) in tpl.tokens.iter().enumerate() {
            match token {
                Token::Literal(s) => out.push_str(s),
                Token::Slot(code) => {
                    let is_tail = tail_idx == Some(idx);
                    let word = if rhyme_line && is_tail {
                        if let Some(rid) = rhyme_id {
                            let rhyme_words = self
                                .rhymes
                                .get(rid)
                                .and_then(|m| m.get(code))
                                .map(|v| v.as_slice());
                            if let Some(words) = rhyme_words {
                                if !words.is_empty() {
                                    pick_distinct(rng, words, code, &mut used)
                                } else {
                                    self.pick_lexicon(rng, code, &mut used)
                                }
                            } else {
                                self.pick_lexicon(rng, code, &mut used)
                            }
                        } else {
                            self.pick_lexicon(rng, code, &mut used)
                        }
                    } else {
                        self.pick_lexicon(rng, code, &mut used)
                    };
                    out.push_str(&word);
                }
            }
        }
        out
    }

    /// 从词库按权重抽取一个词，尽量避免与本句已用词重复。
    fn pick_lexicon(
        &self,
        rng: &mut Rng,
        code: &str,
        used: &mut HashSet<(String, String)>,
    ) -> String {
        let empty: WeightedVec = Vec::new();
        let items = self.lexicon.get(code).unwrap_or(&empty);
        if items.is_empty() {
            return String::new();
        }
        // 仅当词库有足够选择时去重（至少 3 个不同词）。
        if items.len() >= 3 {
            for _ in 0..6 {
                let w = rng.weighted(items);
                if used.insert((code.to_string(), w.clone())) {
                    return w.clone();
                }
            }
        }
        rng.weighted(items).clone()
    }
}

/// 从普通（无权重）词列表中抽取，并做句内去重。
fn pick_distinct(
    rng: &mut Rng,
    words: &[String],
    code: &str,
    used: &mut HashSet<(String, String)>,
) -> String {
    if words.len() >= 3 {
        for _ in 0..6 {
            let w = rng.pick(words);
            if used.insert((code.to_string(), w.clone())) {
                return w.clone();
            }
        }
    }
    rng.pick(words).clone()
}

/// 把 `&Template` 列表转为 weighted() 所需的 `[(&T, u32)]`。
fn to_weighted<'a>(items: &[&'a Template]) -> Vec<(&'a Template, u32)> {
    items.iter().map(|t| (*t, t.weight)).collect()
}
