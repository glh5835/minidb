(() => {
  const theme = document.body.dataset.theme;
  const themeNames = { a: 'A · 玉白松绿', b: 'B · 纸感学院', c: 'C · 墨色实验室' };
  document.getElementById('previewRoot').innerHTML = `
    <div class="prototype-note"><b>${themeNames[theme]}</b><span>设计方向预览 · 示例数据 · 不执行数据库命令</span><div><button class="screen-switch active" data-screen="start">开始页面</button><button class="screen-switch" data-screen="sql">SQL 工作台</button></div></div>
    <div class="preview-app">
      <header class="preview-top"><a>MiniDB <b>Studio</b></a><button class="db-context"><span>当前数据库</span><b>school.db</b></button><span class="top-state"><i></i>本地服务已连接</span><span class="txn-state">自动提交</span><button class="avatar">L</button></header>
      <nav class="preview-nav"><span class="nav-group">工作区</span><button class="active">⌂ <em>开始</em></button><button>▦ <em>数据库管理</em></button><button>⌘ <em>SQL 工作台</em></button><span class="nav-group">分析与观察</span><button>⌘ <em>索引与执行计划</em></button><span class="nav-group">教学实验室</span><button>◫ <em>六类实验</em></button><button class="help">? <em>帮助与说明</em></button></nav>
      <main class="preview-main">
        <section class="preview-screen active" data-preview="start">
          <header class="page-title"><span>工作区概览</span><h1>从一个具体任务开始</h1><p>选择数据库，或继续上次的 SQL 与实验。</p></header>
          <div class="quick-grid"><button class="accent"><i>＋</i><b>创建数据库</b><small>新建本地 .db 文件</small></button><button><i>▤</i><b>打开数据库</b><small>选择已有数据库文件</small></button><button><i>⌘</i><b>继续 SQL 草稿</b><small>student 查询 · 3 分钟前</small></button></div>
          <div class="start-grid"><section class="panel recent"><header><div><h2>最近数据库</h2><p>快速回到上一次的工作对象</p></div><a>浏览文件</a></header><div class="db-row"><span class="db-icon">DB</span><span><b>school.db</b><small>D:/MiniDB-design-review/school.db</small></span><em>工作库</em><time>刚刚</time></div><div class="db-row"><span class="db-icon">DB</span><span><b>library_course_design_2026.db</b><small>D:/Courses/database/…/library_course_design_2026.db</small></span><em class="off">关闭</em><time>昨天</time></div></section><aside class="panel next"><h2>建议下一步</h2><ol><li><b>01</b><span>浏览 student 表<small>确认字段类型与数据</small></span><a>开始</a></li><li><b>02</b><span>执行第一个查询<small>查看结果与计划</small></span><a>打开</a></li><li><b>03</b><span>观察 B+ 树<small>理解索引结构</small></span><a>查看</a></li></ol></aside></div>
          <div class="guide"><span><b>01</b>选择数据库</span><i></i><span><b>02</b>浏览或查询</span><i></i><span><b>03</b>在实验库观察</span></div>
        </section>
        <section class="preview-screen" data-preview="sql">
          <header class="sql-title"><span>school.db / SQL 工作台</span><h1>查询编辑器</h1><em>● 草稿已保存在本机</em></header>
          <div class="sql-layout"><section class="editor panel"><header><b>● student-analysis.sql</b><span>school.db · REPEATABLE READ</span></header><div class="code"><i>1<br>2<br>3<br>4<br>5<br>6</i><pre><code>SELECT s.id, s.name, s.score, c.title AS course_title
FROM student AS s
JOIN score sc ON sc.student_id = s.id
JOIN course c ON c.id = sc.course_id
WHERE s.score &gt;= 80
ORDER BY s.score DESC;</code></pre></div><footer><button class="run">▶ 执行 SQL <kbd>Ctrl+Enter</kbd></button><button>执行选中</button><button>查看计划</button><span></span><button>开始事务</button><em>自动提交</em></footer></section>
            <section class="results panel"><nav><button class="active">结果 <b>6</b></button><button>消息</button><button>执行计划</button><button>历史</button></nav><div class="result-summary"><b>✓ 查询成功 · 6 行</b><span>示例耗时 4 ms（原型值）</span><a>导出 CSV</a></div><div class="table-wrap"><table><thead><tr><th>id <small>INT</small></th><th>name <small>VARCHAR</small></th><th>score <small>DOUBLE</small></th><th>course_title <small>VARCHAR</small></th></tr></thead><tbody><tr><td>2</td><td>王芳</td><td>92</td><td>数据库系统概论</td></tr><tr><td>1</td><td>张伟</td><td>88.5</td><td>数据结构</td></tr><tr><td>5</td><td>陈静</td><td>84</td><td>Java 程序设计</td></tr></tbody></table></div></section>
          </div>
        </section>
      </main>
    </div>`;

  const switches = [...document.querySelectorAll('[data-screen]')];
  switches.forEach((button) => button.addEventListener('click', () => {
    switches.forEach((other) => other.classList.toggle('active', other === button));
    document.querySelectorAll('[data-preview]').forEach((screen) => screen.classList.toggle('active', screen.dataset.preview === button.dataset.screen));
  }));
})();
