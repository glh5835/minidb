/* MiniDB Studio review prototype. All actions are local UI state only. */
(() => {
  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
  const workspace = $('#workspace');
  const toast = $('#toast');
  let toastTimer;

  const showToast = (message) => {
    $('span', toast).textContent = message;
    toast.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => toast.classList.remove('show'), 2600);
  };

  const routeFromHash = () => location.hash.replace(/^#\/?/, '') || 'start';
  const go = (route) => {
    if (location.hash !== `#/${route}`) location.hash = `#/${route}`;
    else renderRoute(route);
  };

  const renderRoute = (route) => {
    const target = $(`[data-route="${route}"]`);
    if (!target) return go('start');
    $$('.view').forEach((view) => view.classList.toggle('active', view === target));
    $$('.nav-item[data-go]').forEach((item) => item.classList.toggle('active', item.dataset.go === route));
    workspace.scrollTo({ top: 0, behavior: 'instant' });
    document.title = `MiniDB Studio · ${target.querySelector('h1')?.textContent || '设计原型'}`;
  };

  $$('[data-go]').forEach((button) => button.addEventListener('click', () => go(button.dataset.go)));
  window.addEventListener('hashchange', () => renderRoute(routeFromHash()));

  $('#toggleLabs').addEventListener('click', (event) => {
    event.stopPropagation();
    $('#labNav').classList.toggle('collapsed');
    event.currentTarget.classList.toggle('collapsed');
  });

  // Database switch popover.
  const dbPopover = $('#dbPopover');
  $('#dbSwitch').addEventListener('click', (event) => {
    event.stopPropagation();
    dbPopover.hidden = !dbPopover.hidden;
  });
  document.addEventListener('click', (event) => {
    if (!dbPopover.hidden && !dbPopover.contains(event.target)) dbPopover.hidden = true;
  });

  // Dialogs and honest prototype actions.
  $$('[data-open]').forEach((button) => button.addEventListener('click', () => {
    const dialog = document.getElementById(button.dataset.open);
    if (dialog?.showModal) dialog.showModal();
  }));
  $$('[data-toast]').forEach((button) => button.addEventListener('click', () => showToast(button.dataset.toast)));

  // Database object selection and tabs.
  $$('.object-item').forEach((item) => item.addEventListener('click', () => {
    $$('.object-item').forEach((other) => other.classList.toggle('active', other === item));
    const name = item.dataset.table;
    $('#currentTable').textContent = name;
    $('#tableHeading').textContent = name;
    if (name !== 'student') showToast(`原型：已切换对象到 ${name}（表格仍使用统一示例数据）`);
  }));
  $$('[data-dbtab]').forEach((tab) => tab.addEventListener('click', () => {
    $$('[data-dbtab]').forEach((other) => other.classList.toggle('active', other === tab));
    $$('[data-dbpanel]').forEach((panel) => panel.classList.toggle('active', panel.dataset.dbpanel === tab.dataset.dbtab));
  }));

  // SQL result tabs and explicit state demonstrations.
  const showSqlState = (state) => {
    $$('.sql-state').forEach((panel) => panel.classList.toggle('active', panel.dataset.sqlstate === state));
    $$('.result-panel').forEach((panel) => panel.classList.remove('active'));
    $('#sqlState').value = state;
    $('#stopSql').disabled = state !== 'running';
  };
  $('#sqlState').addEventListener('change', (event) => showSqlState(event.target.value));

  $$('[data-resulttab]').forEach((tab) => tab.addEventListener('click', () => {
    const name = tab.dataset.resulttab;
    $$('.result-tabs > button').forEach((other) => other.classList.toggle('active', other === tab));
    if (name === 'result') showSqlState($('#sqlState').value);
    else {
      $$('.sql-state').forEach((panel) => panel.classList.remove('active'));
      $$('.result-panel').forEach((panel) => panel.classList.toggle('active', panel.dataset.resultpanel === name));
    }
  }));

  const runSql = () => {
    showSqlState('running');
    showToast('原型：正在演示执行中状态，不会发送 SQL');
    setTimeout(() => {
      showSqlState('success');
      $$('.result-tabs > button').forEach((tab) => tab.classList.toggle('active', tab.dataset.resulttab === 'result'));
    }, 900);
  };
  $('#runSql').addEventListener('click', runSql);
  $('#stopSql').addEventListener('click', () => {
    showSqlState('idle');
    showToast('原型：已演示停止状态，SQL 草稿保持不变');
  });
  document.addEventListener('keydown', (event) => {
    if ((event.ctrlKey || event.metaKey) && event.key === 'Enter' && routeFromHash() === 'sql') {
      event.preventDefault();
      runSql();
    }
  });

  // Transaction controls remain prominent and synchronized with global context.
  let transactionActive = false;
  const syncTransaction = () => {
    $('#beginTxn').disabled = transactionActive;
    $('#commitTxn').disabled = !transactionActive;
    $('#rollbackTxn').disabled = !transactionActive;
    $('#txnLabel').innerHTML = transactionActive
      ? '<i class="status-dot warn"></i>ACTIVE · REPEATABLE READ'
      : '<i class="status-dot"></i>自动提交';
    $('#globalTxn').innerHTML = transactionActive
      ? '<i class="status-dot warn"></i>事务 ACTIVE'
      : '<i class="status-dot"></i>自动提交';
  };
  $('#beginTxn').addEventListener('click', () => { transactionActive = true; syncTransaction(); showToast('原型：已进入事务演示状态'); });
  $('#commitTxn').addEventListener('click', () => { transactionActive = false; syncTransaction(); showToast('原型：已演示提交，不会写入数据库'); });
  $('#rollbackTxn').addEventListener('click', () => { transactionActive = false; syncTransaction(); showToast('原型：已演示回滚，不会写入数据库'); });

  renderRoute(routeFromHash());
  syncTransaction();
})();
