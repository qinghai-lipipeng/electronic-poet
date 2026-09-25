//! poet-core 端到端测试：用临时目录模拟可挂载词库包。

use std::fs;
use std::path::Path;

use poet_core::{generator::RhymeScheme, generator::GenOptions, list_packs, Pack};

fn write(p: &Path, text: &str) {
    fs::create_dir_all(p.parent().unwrap()).unwrap();
    fs::write(p, text).unwrap();
}

/// 构造一个最小可用的测试包。
fn make_pack(root: &Path, with_rhyme: bool) {
    write(
        &root.join("manifest.conf"),
        "id = test\nname = 测试包\nversion = 1.0\nauthor = 测试者\ndescription = 单元测试用\n",
    );
    write(
        &root.join("grammar/modern.txt"),
        "\
# 正文模板
MM在DD
XX的MM在DD
我面对着XX的MM和XX的MM
MMDJMM
TTXX的MM
从MM到MM
让MM变得XX吧
MM是XX的
",
    );
    write(
        &root.join("grammar/title.txt"),
        "XX的MM\nTTMM\n",
    );
    write(
        &root.join("lexicon/MM.txt"),
        "月亮\n灯塔\n浪花\n荒原\n蝴蝶\n星辰\n",
    );
    write(
        &root.join("lexicon/DD.txt"),
        "飞翔\n游荡\n燃烧\n沉没\n歌唱\n生长\n",
    );
    write(&root.join("lexicon/DJ.txt"), "拥抱\n追逐\n照亮\n亲吻\n");
    write(
        &root.join("lexicon/XX.txt"),
        "金色\n荒凉\n温柔\n明亮\n遥远\n寂静\n",
    );
    write(&root.join("lexicon/TT.txt"), "啊！\n噢！\n");

    if with_rhyme {
        // ang 韵：让 DD 尾词可押
        write(
            &root.join("rhyme/ang.txt"),
            "DD=飞翔\nDD=游荡\n",
        );
    }
}

#[test]
fn loads_and_generates_basic_shape() {
    let tmp = tempfile::tempdir().unwrap();
    let root = tmp.path().join("pack1");
    make_pack(&root, false);
    let pack = Pack::load(&root).unwrap();

    assert_eq!(pack.manifest.name, "测试包");
    assert_eq!(pack.word_count("MM"), 6);
    assert_eq!(pack.word_count("DD"), 6);

    let opts = GenOptions {
        paragraphs: 3,
        lines_per_paragraph: 4,
        make_title: true,
        ..Default::default()
    };
    let poem = pack.generate(&opts).unwrap();
    assert_eq!(poem.paragraphs.len(), 3);
    for stanza in &poem.paragraphs {
        assert_eq!(stanza.len(), 4);
        for line in stanza {
            assert!(!line.is_empty());
        }
    }
    assert!(poem.title.is_some());
}

#[test]
fn same_seed_is_reproducible() {
    let tmp = tempfile::tempdir().unwrap();
    let root = tmp.path().join("pack1");
    make_pack(&root, false);
    let pack = Pack::load(&root).unwrap();

    let opts = GenOptions {
        paragraphs: 2,
        lines_per_paragraph: 5,
        seed: Some(42),
        make_title: true,
        ..Default::default()
    };
    let a = pack.generate(&opts).unwrap();
    let b = pack.generate(&opts).unwrap();
    assert_eq!(a.paragraphs, b.paragraphs);
    assert_eq!(a.title, b.title);
    assert_eq!(a.seed, 42);

    // 不同种子应产生不同结果（极小概率偶然相同，循环多取降低偶发）。
    let opts2 = GenOptions {
        seed: Some(43),
        ..opts.clone()
    };
    let c = pack.generate(&opts2).unwrap();
    let mut any_diff = false;
    for (sa, sb) in a.paragraphs.iter().zip(c.paragraphs.iter()) {
        if sa != sb {
            any_diff = true;
        }
    }
    assert!(any_diff);
}

#[test]
fn rhyme_lines_end_with_rhyme_words() {
    let tmp = tempfile::tempdir().unwrap();
    let root = tmp.path().join("pack1");
    make_pack(&root, true);
    let pack = Pack::load(&root).unwrap();

    let opts = GenOptions {
        paragraphs: 2,
        lines_per_paragraph: 4,
        rhyme: true,
        rhyme_id: Some("ang".to_string()),
        rhyme_scheme: RhymeScheme::Every,
        per_paragraph_rhyme: false,
        make_title: false,
        seed: Some(7),
        ..Default::default()
    };
    let poem = pack.generate(&opts).unwrap();
    assert!(poem.warnings.is_empty());
    assert!(poem.rhymes.iter().all(|r| r.as_deref() == Some("ang")));

    // 模板 "MM在DD" 是唯一以 DD 结尾且可押 ang 的模板，
    // 押韵行应以 飞翔/游荡 结尾。
    for stanza in &poem.paragraphs {
        for line in stanza {
            assert!(line.ends_with("飞翔") || line.ends_with("游荡"), "行尾未押韵：{line}");
        }
    }
}

/// 句中出现与句尾同名的占位符时，只有句尾从韵库取词，句内仍从词库取词。
#[test]
fn repeated_slot_uses_lexicon_except_tail() {
    let tmp = tempfile::tempdir().unwrap();
    let root = tmp.path().join("pack1");
    write(&root.join("manifest.conf"), "id = repeat\nname = 重复占位符\n");
    write(&root.join("grammar/modern.txt"), "从MM到MM\n");
    write(&root.join("lexicon/MM.txt"), "月亮\n灯塔\n荒原\n");
    write(&root.join("rhyme/gu.txt"), "MM=旅途\nMM=归途\n");
    let pack = Pack::load(&root).unwrap();

    let opts = GenOptions {
        paragraphs: 1,
        lines_per_paragraph: 4,
        rhyme: true,
        rhyme_id: Some("gu".to_string()),
        rhyme_scheme: RhymeScheme::Every,
        per_paragraph_rhyme: false,
        make_title: false,
        seed: Some(3),
        ..Default::default()
    };
    let poem = pack.generate(&opts).unwrap();
    let stanza = &poem.paragraphs[0];
    assert_eq!(stanza.len(), 4);
    for line in stanza {
        // "从MM到MM" → 从 + 词 + 到 + 词
        let chars: Vec<char> = line.chars().collect();
        assert_eq!(chars.len(), 6, "行长应为 6 字：{line}");
        let inner: String = chars[1..3].iter().collect();
        let tail: String = chars[4..6].iter().collect();
        assert!(
            ["月亮", "灯塔", "荒原"].contains(&inner.as_str()),
            "句内占位符应取自词库：{line}"
        );
        assert!(
            ["旅途", "归途"].contains(&tail.as_str()),
            "句尾占位符应取自韵库：{line}"
        );
    }
}

/// 句中出现同名占位符且该代码没有词库时，模板不可押韵（否则句内会填出空词）。
#[test]
fn repeated_slot_without_lexicon_is_not_rhymable() {
    let tmp = tempfile::tempdir().unwrap();
    let root = tmp.path().join("pack1");
    write(&root.join("manifest.conf"), "id = repeat\nname = 重复占位符\n");
    // MM 只有韵库没有词库；可用模板 XX的TT 的句尾 TT 不在韵库中。
    write(&root.join("grammar/modern.txt"), "从MM到MM\nXX的TT\n");
    write(&root.join("lexicon/XX.txt"), "温柔\n明亮\n遥远\n");
    write(&root.join("lexicon/TT.txt"), "啊！\n噢！\n");
    write(&root.join("rhyme/gu.txt"), "MM=旅途\nMM=归途\n");
    let pack = Pack::load(&root).unwrap();

    let opts = GenOptions {
        paragraphs: 1,
        lines_per_paragraph: 3,
        rhyme: true,
        rhyme_id: Some("gu".to_string()),
        make_title: false,
        seed: Some(5),
        ..Default::default()
    };
    let poem = pack.generate(&opts).unwrap();
    // 没有可用韵部 → 回退为不押韵并给出提示。
    assert_eq!(poem.warnings.len(), 1, "应给出押韵回退提示：{:?}", poem.warnings);
    for line in &poem.paragraphs[0] {
        assert!(
            !line.contains("旅途") && !line.contains("归途"),
            "不可押韵的模板不应被选中：{line}"
        );
        assert!(line.contains('的'), "应使用可用模板 XX的TT：{line}");
    }
}

/// 未指定种子时，返回的 seed 必须是本次实际使用的种子，可用于复现。
#[test]
fn unspecified_seed_is_reported_for_replay() {
    let tmp = tempfile::tempdir().unwrap();
    let root = tmp.path().join("pack1");
    make_pack(&root, false);
    let pack = Pack::load(&root).unwrap();

    let opts = GenOptions {
        paragraphs: 2,
        lines_per_paragraph: 3,
        make_title: true,
        seed: None,
        ..Default::default()
    };
    let poem = pack.generate(&opts).unwrap();
    let replay = pack
        .generate(&GenOptions {
            seed: Some(poem.seed),
            ..opts.clone()
        })
        .unwrap();
    assert_eq!(poem.title, replay.title);
    assert_eq!(poem.paragraphs, replay.paragraphs);
    assert_eq!(poem.rhymes, replay.rhymes);
}

/// begin_generate 的 JSON 须为 camelCase 的 `title` / `seed`（Kotlin 侧按此解析）。
#[test]
fn begin_out_json_shape() {
    let tmp = tempfile::tempdir().unwrap();
    let root = tmp.path().join("pack1");
    make_pack(&root, false);
    let pack = Pack::load(&root).unwrap();

    let opts = GenOptions {
        paragraphs: 1,
        lines_per_paragraph: 2,
        make_title: true,
        seed: Some(1234),
        ..Default::default()
    };
    let begin = pack.begin_generate(&opts).unwrap();
    assert_eq!(begin.seed, 1234);
    assert!(begin.title.is_some());

    let json = serde_json::to_value(&begin).unwrap();
    assert_eq!(json["seed"].as_u64(), Some(1234));
    assert!(json["title"].is_string(), "标题应序列化为 JSON 字符串：{json}");
}

#[test]
fn alternate_scheme_rhymes_only_even_lines() {
    let tmp = tempfile::tempdir().unwrap();
    let root = tmp.path().join("pack1");
    make_pack(&root, true);
    let pack = Pack::load(&root).unwrap();

    let opts = GenOptions {
        paragraphs: 1,
        lines_per_paragraph: 4,
        rhyme: true,
        rhyme_id: Some("ang".to_string()),
        rhyme_scheme: RhymeScheme::Alternate,
        per_paragraph_rhyme: false,
        make_title: false,
        seed: Some(11),
        ..Default::default()
    };
    let poem = pack.generate(&opts).unwrap();
    let stanza = &poem.paragraphs[0];
    // 第 2、4 行（索引 1、3）必须押韵。
    assert!(stanza[1].ends_with("飞翔") || stanza[1].ends_with("游荡"));
    assert!(stanza[3].ends_with("飞翔") || stanza[3].ends_with("游荡"));
}

#[test]
fn missing_lexicon_marks_templates_unusable() {
    let tmp = tempfile::tempdir().unwrap();
    let root = tmp.path().join("pack1");
    make_pack(&root, false);
    // 删掉 DD 词库：所有含 DD 的模板失效，但仍有 MMDJMM / TTXX的MM 可用。
    fs::remove_file(root.join("lexicon/DD.txt")).unwrap();
    let pack = Pack::load(&root).unwrap();

    let opts = GenOptions {
        paragraphs: 1,
        lines_per_paragraph: 3,
        seed: Some(1),
        make_title: false,
        ..Default::default()
    };
    let poem = pack.generate(&opts).unwrap();
    for line in &poem.paragraphs[0] {
        assert!(!line.contains("在飞翔") && !line.contains("在游荡"));
        assert!(!line.is_empty());
    }

    // 再删掉全部词库 → 没有可用模板，应报错。
    for f in ["MM.txt", "DJ.txt", "XX.txt", "TT.txt"] {
        fs::remove_file(root.join("lexicon").join(f)).unwrap();
    }
    let pack2 = Pack::load(&root).unwrap();
    assert!(pack2.generate(&opts).is_err());
}

#[test]
fn lists_packs_under_mount_root() {
    let tmp = tempfile::tempdir().unwrap();
    make_pack(&tmp.path().join("alpha"), false);
    make_pack(&tmp.path().join("beta"), true);
    // 一个普通目录（无 manifest）不应出现。
    fs::create_dir_all(tmp.path().join("not-a-pack")).unwrap();

    let packs = list_packs(tmp.path());
    let ids: Vec<&str> = packs.iter().map(|p| p.path.as_str()).collect();
    assert_eq!(packs.len(), 2);
    assert!(ids.iter().any(|p| p.ends_with("alpha")));
    assert!(ids.iter().any(|p| p.ends_with("beta")));

    let beta = packs.iter().find(|p| p.path.ends_with("beta")).unwrap();
    assert!(beta.rhymes.contains(&"ang".to_string()));

    // JSON 键须为 camelCase：Kotlin 侧按 titleTemplates / lexiconWords / lexiconCodes 解析。
    let json = serde_json::to_value(beta).unwrap();
    for key in ["titleTemplates", "lexiconWords", "lexiconCodes"] {
        assert!(json.get(key).is_some(), "缺少 camelCase 字段 {key}：{json}");
    }
}

#[test]
fn swapping_packs_changes_output_domain() {
    let tmp = tempfile::tempdir().unwrap();
    let a_root = tmp.path().join("a");
    let b_root = tmp.path().join("b");
    make_pack(&a_root, false);

    // 第二个包：词库完全不同。
    write(
        &b_root.join("manifest.conf"),
        "id = other\nname = 另一个\n",
    );
    write(&b_root.join("grammar/modern.txt"), "MM在DD\n");
    write(&b_root.join("lexicon/MM.txt"), "电路\n芯片\n");
    write(&b_root.join("lexicon/DD.txt"), "短路\n放电\n");

    let pack_a = Pack::load(&a_root).unwrap();
    let pack_b = Pack::load(&b_root).unwrap();

    let opts = GenOptions {
        paragraphs: 1,
        lines_per_paragraph: 6,
        seed: Some(99),
        make_title: false,
        ..Default::default()
    };
    let poem_b = pack_b.generate(&opts).unwrap();
    for line in &poem_b.paragraphs[0] {
        assert!(
            (line.contains("电路") || line.contains("芯片"))
                && (line.contains("短路") || line.contains("放电")),
            "未使用新挂载包词库：{line}"
        );
    }
    // 确认 A 包句柄不受影响（包之间相互独立）。
    let poem_a = pack_a.generate(&opts).unwrap();
    assert_eq!(poem_a.pack_id, "test");
    assert_eq!(poem_b.pack_id, "other");
}
