/* ============================================================
   生活优选 · 前端脚本（原生 JS，无构建依赖）
   接口与后端同源（8081），token 存在 localStorage
   ============================================================ */
(() => {
  'use strict';

  const app = document.getElementById('app');
  const topActions = document.getElementById('topbar-actions');
  const modalRoot = document.getElementById('modal-root');
  const toastRoot = document.getElementById('toast-root');

  const state = {
    token: localStorage.getItem('yx_token') || '',
    user: null,
    homeTimer: null,
  };

  class AuthError extends Error {}

  /* ---------------------------- 工具函数 ---------------------------- */

  function esc(value) {
    return String(value == null ? '' : value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  const GRADIENTS = [
    ['#f7c59f', '#ef8354'],
    ['#d9c3a5', '#a98467'],
    ['#f2d0a4', '#e09f5c'],
    ['#e6cfc1', '#c39a86'],
    ['#d7e3d4', '#9db8a1'],
    ['#f4d6c6', '#d98b6a'],
    ['#e3d5c8', '#b8a08b'],
    ['#f6c6ac', '#e0784f'],
  ];

  function gradientFor(seed) {
    const text = String(seed || '店');
    let n = 0;
    for (let i = 0; i < text.length; i++) n = (n * 31 + text.charCodeAt(i)) >>> 0;
    return GRADIENTS[n % GRADIENTS.length];
  }

  function gradStyle(seed) {
    const [c1, c2] = gradientFor(seed);
    return `--c1:${c1};--c2:${c2}`;
  }

  function firstChar(name) {
    const text = String(name || '店').trim();
    return text ? [...text][0] : '店';
  }

  function firstImage(images) {
    if (!images) return '';
    return String(images).split(',')[0].trim();
  }

  function pickImage(images) {
    const url = firstImage(images);
    if (!url) return '';
    return `<img src="${esc(url)}" alt="" loading="lazy" referrerpolicy="no-referrer" onerror="this.remove()">`;
  }

  function money(fen) {
    const value = Number(fen || 0) / 100;
    return Number.isInteger(value) ? String(value) : value.toFixed(2);
  }

  function scoreText(score) {
    const n = Number(score);
    return Number.isFinite(n) && n > 0 ? (n / 10).toFixed(1) : '—';
  }

  function shortTime(value) {
    if (!value) return '';
    return String(value).replace('T', ' ').slice(0, 16);
  }

  function emptyState(text, icon = '🍃') {
    return `<div class="empty"><div class="big">${icon}</div>${esc(text)}</div>`;
  }

  function skeletonCards(count, cover = true) {
    return Array.from({ length: count }, () => `
      <div class="skeleton">
        ${cover ? '<div class="skel-line skel-cover"></div>' : ''}
        <div class="skel-line" style="width:70%"></div>
        <div class="skel-line" style="width:45%"></div>
        <div class="skel-line" style="width:55%"></div>
      </div>`).join('');
  }

  function toast(message, isError = false) {
    const el = document.createElement('div');
    el.className = 'toast' + (isError ? ' err' : '');
    el.textContent = message;
    toastRoot.appendChild(el);
    setTimeout(() => {
      el.style.transition = 'opacity .25s, transform .25s';
      el.style.opacity = '0';
      el.style.transform = 'translateY(8px)';
      setTimeout(() => el.remove(), 260);
    }, 2400);
  }

  /* ---------------------------- 请求 ---------------------------- */

  async function api(path, options = {}) {
    const headers = { Accept: 'application/json' };
    if (options.body !== undefined) headers['Content-Type'] = 'application/json';
    if (state.token) headers.authorization = state.token;

    const response = await fetch(path, {
      method: options.method || 'GET',
      headers,
      body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
    });

    if (response.status === 401) throw new AuthError('请先登录');

    const text = await response.text();
    let json = null;
    try {
      json = JSON.parse(text);
    } catch (e) {
      throw new Error('服务返回异常，请确认后端已启动');
    }
    if (!json.success) throw new Error(json.errorMsg || '请求失败');
    return json.data;
  }

  function handleError(error) {
    if (error instanceof AuthError) {
      toast('请先登录', true);
      openLogin();
      return;
    }
    toast(error.message || '出了点问题', true);
  }

  /* ---------------------------- 顶栏 & 登录态 ---------------------------- */

  function avatarHtml(user, sizeClass = '') {
    const name = user && user.nickName ? user.nickName : '游';
    const icon = user && user.icon ? user.icon : '';
    return `<span class="avatar ${sizeClass}">
      ${icon ? `<img src="${esc(icon)}" alt="" onerror="this.remove()">` : ''}
      <span class="fallback">${esc(firstChar(name))}</span>
    </span>`;
  }

  function renderTopbar() {
    if (state.user) {
      topActions.innerHTML = `
        <a class="user-chip" href="#/me">
          ${avatarHtml(state.user)}
          <span>${esc(state.user.nickName || '用户')}</span>
        </a>`;
    } else {
      topActions.innerHTML = `<button class="btn btn-primary btn-sm" data-act="login">登录 / 注册</button>`;
    }
  }

  async function ensureUser() {
    if (!state.token) {
      state.user = null;
      return null;
    }
    try {
      state.user = await api('/user/me');
    } catch (e) {
      if (e instanceof AuthError) {
        state.token = '';
        state.user = null;
        localStorage.removeItem('yx_token');
      }
    }
    renderTopbar();
    return state.user;
  }

  /* ---------------------------- 登录弹层 ---------------------------- */

  let sendTimer = null;

  function openLogin() {
    if (document.getElementById('login-phone')) return;
    const lastPhone = localStorage.getItem('yx_phone') || '';
    modalRoot.innerHTML = `
      <div class="modal-overlay" data-act="close-modal">
        <div class="modal" data-stop="1">
          <h3>登录生活优选</h3>
          <p class="modal-desc">手机号验证码登录，未注册的号码会自动创建账户。</p>
          <div class="field">
            <label for="login-phone">手机号</label>
            <input id="login-phone" class="input" inputmode="numeric" maxlength="11"
                   placeholder="请输入 11 位手机号" value="${esc(lastPhone)}" autocomplete="tel">
          </div>
          <div class="field">
            <label for="login-code">验证码</label>
            <div class="code-row">
              <input id="login-code" class="input" inputmode="numeric" maxlength="6"
                     placeholder="6 位验证码" autocomplete="one-time-code">
              <button class="btn btn-ghost btn-sm" id="send-code" style="min-width:104px">获取验证码</button>
            </div>
          </div>
          <div class="hint">
            默认是<b>模拟短信</b>模式：验证码会打印在<b>后端控制台</b>（IDEA 的 Run 窗口，搜索「模拟短信」）；
            配置阿里云短信后，验证码会直接发到手机上。
          </div>
          <div class="modal-actions">
            <button class="btn btn-primary" id="do-login">登录</button>
          </div>
        </div>
      </div>`;

    document.getElementById('send-code').addEventListener('click', onSendCode);
    document.getElementById('do-login').addEventListener('click', onLogin);
    document.getElementById('login-code').addEventListener('keydown', (e) => {
      if (e.key === 'Enter') onLogin();
    });
    document.getElementById('login-phone').focus();
  }

  function closeLogin() {
    modalRoot.innerHTML = '';
  }

  async function onSendCode() {
    const phone = document.getElementById('login-phone').value.trim();
    if (!/^1\d{10}$/.test(phone)) {
      toast('请输入正确的手机号', true);
      return;
    }
    const button = document.getElementById('send-code');
    try {
      button.disabled = true;
      await api(`/user/code?phone=${encodeURIComponent(phone)}`, { method: 'POST' });
      localStorage.setItem('yx_phone', phone);
      toast('验证码已发送，请注意查收');
      let left = 60;
      button.textContent = left + 's 后重发';
      clearInterval(sendTimer);
      sendTimer = setInterval(() => {
        left -= 1;
        if (left <= 0) {
          clearInterval(sendTimer);
          button.disabled = false;
          button.textContent = '获取验证码';
        } else {
          button.textContent = left + 's 后重发';
        }
      }, 1000);
    } catch (e) {
      button.disabled = false;
      toast(e.message, true);
    }
  }

  async function onLogin() {
    const phone = document.getElementById('login-phone').value.trim();
    const code = document.getElementById('login-code').value.trim();
    if (!/^1\d{10}$/.test(phone)) return toast('请输入正确的手机号', true);
    if (!/^\d{6}$/.test(code)) return toast('请输入 6 位验证码', true);

    const button = document.getElementById('do-login');
    try {
      button.disabled = true;
      button.textContent = '登录中…';
      const token = await api('/user/login', { method: 'POST', body: { phone, code } });
      state.token = token;
      localStorage.setItem('yx_token', token);
      localStorage.setItem('yx_phone', phone);
      await ensureUser();
      closeLogin();
      toast('登录成功，欢迎回来');
      route();
    } catch (e) {
      toast(e.message, true);
    } finally {
      button.disabled = false;
      button.textContent = '登录';
    }
  }

  async function logout() {
    try {
      await api('/user/logout', { method: 'POST' });
    } catch (e) {
      /* 忽略登出失败，本地状态照清 */
    }
    state.token = '';
    state.user = null;
    localStorage.removeItem('yx_token');
    renderTopbar();
    toast('已退出登录');
    location.hash = '#/';
  }

  /* ---------------------------- 卡片模板 ---------------------------- */

  const TYPE_EMOJI = {
    美食: '🍜',
    KTV: '🎤',
    '丽人·美发': '💇',
    '美睫·美甲': '💅',
    '按摩·足疗': '💆',
    '健身运动': '🏃',
    '亲子乐园': '🎠',
    酒吧: '🍸',
    轰趴馆: '🎉',
    'SPA': '🧖',
  };

  function typeEmoji(name) {
    if (TYPE_EMOJI[name]) return TYPE_EMOJI[name];
    if (name && name.indexOf('SPA') >= 0) return '🧖';
    return '✨';
  }

  function shopCard(shop) {
    const distance = shop.distance ? `<span class="dist-badge">${(shop.distance / 1000).toFixed(1)} km</span>` : '';
    return `
      <a class="shop-card fade-in" href="#/shop/${shop.id}">
        <div class="shop-cover" style="${gradStyle(shop.name)}">
          <span class="ph">${esc(firstChar(shop.name))}</span>
          ${pickImage(shop.images)}
          ${distance}
        </div>
        <div class="shop-body">
          <h3 class="shop-name">${esc(shop.name)}</h3>
          <div class="shop-meta">
            <span class="rating">★ ${scoreText(shop.score)}</span>
            <span>¥${esc(shop.avgPrice || '—')}/人</span>
          </div>
          <div class="shop-foot">
            <span>${esc(shop.area || '')}</span>
            <span class="price-sold">已售 <b>${esc(Number(shop.sold || 0))}</b></span>
          </div>
        </div>
      </a>`;
  }

  function blogCard(blog) {
    const authorName = blog.name || '匿名用户';
    return `
      <a class="blog-card fade-in" href="#/blog/${blog.id}">
        <div class="blog-cover" style="${gradStyle(blog.title)}">
          <span class="ph">${esc(firstChar(blog.title || '探'))}</span>
          ${pickImage(blog.images)}
        </div>
        <div class="blog-body">
          <h3 class="blog-title">${esc(blog.title || '无标题笔记')}</h3>
          <div class="blog-meta">
            <span class="author">
              ${avatarHtml({ nickName: authorName, icon: blog.icon })}
              <span>${esc(authorName)}</span>
            </span>
            <span class="like-count">♥ ${esc(blog.liked || 0)}</span>
          </div>
        </div>
      </a>`;
  }

  /* ---------------------------- 首页 ---------------------------- */

  async function renderHome() {
    app.innerHTML = `
      <section class="hero fade-in">
        <span class="hero-eyebrow">Local Life · 生活优选</span>
        <h1>发现身边的<em>好店</em>，<br>也发现生活的小确幸</h1>
        <p>美食、KTV、美发美甲、按摩足疗…… 一站式浏览本地好店，领券更划算。</p>
        <form class="hero-search" id="hero-search">
          <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="11" cy="11" r="7"/><path d="m20 20-3.2-3.2"/></svg>
          <input id="hero-q" type="search" placeholder="搜索店铺名称，比如「烧烤」" autocomplete="off">
          <button class="btn btn-primary btn-sm" type="submit">搜索</button>
        </form>
        <div class="hero-stats" id="hero-stats">
          <div><b>—</b><span>本地品类</span></div>
          <div><b>—</b><span>人气笔记</span></div>
          <div><b>秒杀</b><span>限时优惠券</span></div>
        </div>
      </section>

      <section class="section">
        <div class="cats" id="cats">${Array.from({ length: 8 }, () => '<div class="skeleton" style="height:88px"></div>').join('')}</div>
      </section>

      <section class="section">
        <div class="section-head">
          <div>
            <h2 class="section-title" id="shops-title">附近好店</h2>
            <p class="section-sub">精选本地口碑店铺</p>
          </div>
        </div>
        <div class="grid-shops" id="shops">${skeletonCards(4)}</div>
      </section>

      <section class="section">
        <div class="section-head">
          <div>
            <h2 class="section-title">热门探店</h2>
            <p class="section-sub">看看大家都在写什么</p>
          </div>
        </div>
        <div class="grid-blogs" id="blogs">${skeletonCards(3)}</div>
      </section>`;

    document.getElementById('hero-search').addEventListener('submit', (event) => {
      event.preventDefault();
      const keyword = document.getElementById('hero-q').value.trim();
      if (!keyword) return;
      location.hash = '#/search?q=' + encodeURIComponent(keyword);
    });

    const [types, hotBlogs] = await Promise.all([
      api('/shop-type/list').catch(() => []),
      api('/blog/hot?current=1').catch(() => []),
    ]);

    const stats = document.getElementById('hero-stats');
    if (stats) {
      stats.innerHTML = `
        <div><b>${types.length || '—'}</b><span>本地品类</span></div>
        <div><b>${hotBlogs.length || '—'}</b><span>人气笔记</span></div>
        <div><b>秒杀</b><span>限时优惠券</span></div>`;
    }

    const cats = document.getElementById('cats');
    if (!types.length) {
      cats.innerHTML = emptyState('暂无分类');
    } else {
      cats.innerHTML = types.map((type) => `
        <button class="cat" data-act="switch-type" data-type="${type.id}" data-name="${esc(type.name)}">
          <span class="cat-icon">${typeEmoji(type.name)}</span>
          <span class="cat-name">${esc(type.name)}</span>
        </button>`).join('');
      cats.querySelector('.cat').classList.add('active');
      loadShops(types[0].id, types[0].name);
    }

    const blogs = document.getElementById('blogs');
    blogs.innerHTML = hotBlogs.length
      ? hotBlogs.map(blogCard).join('')
      : emptyState('还没有探店笔记', '📝');
  }

  async function loadShops(typeId, typeName) {
    const shops = document.getElementById('shops');
    const title = document.getElementById('shops-title');
    if (!shops) return;
    if (title) title.textContent = typeName ? `${typeName} · 好店推荐` : '附近好店';
    shops.innerHTML = skeletonCards(4);
    try {
      const list = await api(`/shop/of/type?typeId=${encodeURIComponent(typeId)}&current=1`);
      shops.innerHTML = list && list.length ? list.map(shopCard).join('') : emptyState('这个分类暂时没有店铺');
    } catch (e) {
      shops.innerHTML = emptyState(e.message, '⚠️');
    }
  }

  /* ---------------------------- 搜索 ---------------------------- */

  async function renderSearch(keyword) {
    const q = keyword || '';
    app.innerHTML = `
      <a class="crumb" href="#/">← 返回首页</a>
      <section class="section" style="margin-top:0">
        <div class="section-head">
          <div>
            <h2 class="section-title">“${esc(q)}” 的搜索结果</h2>
            <p class="section-sub">按店铺名称匹配</p>
          </div>
        </div>
        <div class="grid-shops" id="shops">${skeletonCards(4)}</div>
      </section>`;
    try {
      const list = await api(`/shop/of/name?name=${encodeURIComponent(q)}&current=1`);
      document.getElementById('shops').innerHTML = list && list.length
        ? list.map(shopCard).join('')
        : emptyState('没有找到相关店铺，换个关键词试试', '🔍');
    } catch (e) {
      document.getElementById('shops').innerHTML = emptyState(e.message, '⚠️');
    }
  }

  /* ---------------------------- 店铺详情 ---------------------------- */

  async function renderShop(id) {
    app.innerHTML = `<a class="crumb" href="#/">← 返回首页</a>${skeletonCards(1)}`;
    try {
      const [shop, vouchers] = await Promise.all([
        api(`/shop/${encodeURIComponent(id)}`),
        api(`/voucher/list/${encodeURIComponent(id)}`).catch(() => []),
      ]);
      if (!shop) throw new Error('店铺不存在');

      app.innerHTML = `
        <a class="crumb" href="#/">← 返回首页</a>
        <section class="shop-hero fade-in">
          <div class="shop-hero-media" style="${gradStyle(shop.name)}">
            <span class="ph">${esc(firstChar(shop.name))}</span>
            ${pickImage(shop.images)}
          </div>
          <div class="shop-hero-info">
            <h1>${esc(shop.name)}</h1>
            <div class="rating-row">
              <span class="score-pill">★ ${scoreText(shop.score)}</span>
              <span>¥${esc(shop.avgPrice || '—')}/人</span>
              <span>已售 ${esc(Number(shop.sold || 0))}</span>
              <span>${esc(shop.comments || 0)} 条评价</span>
            </div>
            <div class="tags">
              ${shop.area ? `<span class="tag">${esc(shop.area)}</span>` : ''}
              <span class="tag">本地生活</span>
              <span class="tag">支持领券</span>
            </div>
            <div class="info-lines">
              <div class="info-line">
                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 21s7-5.4 7-11a7 7 0 1 0-14 0c0 5.6 7 11 7 11Z"/><circle cx="12" cy="10" r="2.5"/></svg>
                <span><b>${esc(shop.address || '地址待完善')}</b></span>
              </div>
              <div class="info-line">
                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></svg>
                <span>营业时间 <b>${esc(shop.openHours || '—')}</b></span>
              </div>
            </div>
          </div>
        </section>

        <section class="section">
          <div class="section-head">
            <div>
              <h2 class="section-title">优惠券</h2>
              <p class="section-sub">先领券，到店更划算</p>
            </div>
          </div>
          <div class="voucher-grid" id="vouchers">
            ${vouchers && vouchers.length ? vouchers.map(voucherCard).join('') : emptyState('这家店暂时没有优惠券', '🎫')}
          </div>
        </section>`;

      startCountdowns();
    } catch (e) {
      app.innerHTML = `<a class="crumb" href="#/">← 返回首页</a>${emptyState(e.message, '⚠️')}`;
    }
  }

  function voucherCard(voucher) {
    const isSeckill = Number(voucher.type) === 1;
    const stock = Number(voucher.stock || 0);
    const now = Date.now();
    const begin = voucher.beginTime ? new Date(voucher.beginTime).getTime() : null;
    const end = voucher.endTime ? new Date(voucher.endTime).getTime() : null;

    let stateText = '';
    let button = '';
    let deadline = '';

    if (!isSeckill) {
      stateText = '到店核销';
      button = `<button class="btn btn-ghost btn-sm" disabled>立即领取</button>`;
    } else if (begin && now < begin) {
      stateText = '即将开抢';
      button = `<button class="btn btn-primary btn-sm" disabled>未开始</button>`;
      deadline = `data-deadline="${begin}" data-mode="begin"`;
    } else if (end && now > end) {
      stateText = '活动已结束';
      button = `<button class="btn btn-ghost btn-sm" disabled>已结束</button>`;
    } else if (stock <= 0) {
      stateText = '库存已抢光';
      button = `<button class="btn btn-ghost btn-sm" disabled>已抢光</button>`;
    } else {
      stateText = `剩余 ${stock} 张`;
      button = `<button class="btn btn-primary btn-sm" data-act="seckill" data-voucher="${voucher.id}">立即抢购</button>`;
      if (end) deadline = `data-deadline="${end}" data-mode="end"`;
    }

    return `
      <article class="voucher fade-in">
        <div class="voucher-left">
          <span class="voucher-price"><small>¥</small>${money(voucher.payValue)}</span>
          <span class="voucher-origin">¥${money(voucher.actualValue)}</span>
        </div>
        <div class="voucher-body">
          <div class="voucher-title">${esc(voucher.title || '优惠券')}</div>
          <div class="voucher-sub">${esc(voucher.subTitle || voucher.rules || '')}</div>
          <div class="voucher-rule">${esc(voucher.rules || '')}</div>
          <div class="voucher-actions">
            <span class="voucher-state">
              ${deadline
                ? `<span class="countdown" ${deadline}></span>`
                : esc(stateText)}
            </span>
            ${button}
          </div>
        </div>
      </article>`;
  }

  let countdownTimer = null;

  function startCountdowns() {
    clearInterval(countdownTimer);
    const tick = () => {
      document.querySelectorAll('.countdown[data-deadline]').forEach((el) => {
        const target = Number(el.dataset.deadline);
        const diff = target - Date.now();
        const mode = el.dataset.mode === 'begin' ? '距开始 ' : '距结束 ';
        if (diff <= 0) {
          el.textContent = el.dataset.mode === 'begin' ? '已开抢，刷新查看' : '已结束';
          return;
        }
        const sec = Math.floor(diff / 1000);
        const h = Math.floor(sec / 3600);
        const m = Math.floor((sec % 3600) / 60);
        const s = sec % 60;
        el.textContent = mode + [h, m, s].map((n) => String(n).padStart(2, '0')).join(':');
      });
    };
    tick();
    countdownTimer = setInterval(tick, 1000);
  }

  async function seckill(voucherId, button) {
    if (!state.user) {
      toast('登录后才能抢券', true);
      openLogin();
      return;
    }
    const original = button.textContent;
    button.disabled = true;
    button.textContent = '抢购中…';
    try {
      const orderId = await api(`/voucher-order/seckill/${encodeURIComponent(voucherId)}`, { method: 'POST' });
      button.textContent = '已抢到';
      toast(`抢购成功，订单号 ${orderId}`);
    } catch (e) {
      button.disabled = false;
      button.textContent = original;
      if (e instanceof AuthError) {
        state.token = '';
        state.user = null;
        localStorage.removeItem('yx_token');
        renderTopbar();
      }
      toast(e.message, true);
    }
  }

  /* ---------------------------- 笔记详情 ---------------------------- */

  async function renderBlog(id) {
    app.innerHTML = `<a class="crumb" href="#/">← 返回首页</a><div class="skeleton"><div class="skel-line" style="height:26px;width:60%"></div><div class="skel-line"></div><div class="skel-line"></div></div>`;
    try {
      const blog = await api(`/blog/${encodeURIComponent(id)}`);
      if (!blog) throw new Error('笔记不存在');

      const images = String(blog.images || '').split(',').filter(Boolean);
      const authorName = blog.name || '匿名用户';

      app.innerHTML = `
        <a class="crumb" href="#/">← 返回首页</a>
        <article class="article fade-in">
          <h1>${esc(blog.title || '无标题笔记')}</h1>
          <div class="article-meta">
            <span class="author">
              ${avatarHtml({ nickName: authorName, icon: blog.icon })}
              <span>${esc(authorName)}</span>
            </span>
            <span>·</span>
            <span>${esc(shortTime(blog.createTime))}</span>
          </div>
          ${images.length ? `<div class="article-imgs">${images.map((src) => `
            <div class="img"><img src="${esc(src)}" alt="" loading="lazy" referrerpolicy="no-referrer" onerror="this.remove()"></div>`).join('')}</div>` : ''}
          <div class="article-content">${esc(blog.content || '')}</div>
          <div class="article-actions">
            <button class="like-btn ${blog.isLike ? 'liked' : ''}" id="like-btn">
              <svg width="17" height="17" viewBox="0 0 24 24" fill="${blog.isLike ? 'currentColor' : 'none'}" stroke="currentColor" stroke-width="2" stroke-linejoin="round"><path d="M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1-1.1a5.5 5.5 0 0 0-7.8 7.8l1 1.1L12 21l7.8-7.5 1-1.1a5.5 5.5 0 0 0 0-7.8Z"/></svg>
              <span id="like-text">${blog.isLike ? '已点赞' : '点赞'}</span>
              <span id="like-num">${esc(blog.liked || 0)}</span>
            </button>
          </div>
        </article>`;

      document.getElementById('like-btn').addEventListener('click', () => toggleLike(blog.id));
    } catch (e) {
      app.innerHTML = `<a class="crumb" href="#/">← 返回首页</a>${emptyState(e.message, '⚠️')}`;
    }
  }

  async function toggleLike(blogId) {
    if (!state.user) {
      toast('登录后才能点赞', true);
      openLogin();
      return;
    }
    const button = document.getElementById('like-btn');
    const text = document.getElementById('like-text');
    const num = document.getElementById('like-num');
    button.disabled = true;
    try {
      await api(`/blog/like/${encodeURIComponent(blogId)}`, { method: 'PUT' });
      const liked = !button.classList.contains('liked');
      button.classList.toggle('liked', liked);
      text.textContent = liked ? '已点赞' : '点赞';
      num.textContent = String(Math.max(0, Number(num.textContent) + (liked ? 1 : -1)));
      button.querySelector('svg').setAttribute('fill', liked ? 'currentColor' : 'none');
    } catch (e) {
      handleError(e);
    } finally {
      button.disabled = false;
    }
  }

  /* ---------------------------- 我的 ---------------------------- */

  async function renderMe() {
    const user = await ensureUser();
    if (!user) {
      app.innerHTML = `
        <section class="section" style="margin-top:26px">
          <div class="panel" style="text-align:center;padding:56px 24px">
            <div style="font-size:40px;margin-bottom:12px">👋</div>
            <h2 class="section-title" style="margin-bottom:6px">登录后查看你的专属空间</h2>
            <p class="section-sub" style="margin-bottom:22px">签到、我的笔记、抢到的优惠券都在这里</p>
            <button class="btn btn-primary" data-act="login">手机号登录</button>
          </div>
        </section>`;
      return;
    }

    app.innerHTML = `
      <section class="me-grid">
        <div class="panel profile">
          <span class="avatar-lg"><span>${esc(firstChar(user.nickName))}</span>${user.icon ? `<img src="${esc(user.icon)}" alt="" onerror="this.remove()">` : ''}</span>
          <div>
            <h2>${esc(user.nickName || '生活优选用户')}</h2>
            <p>用户 ID：${esc(user.id)}</p>
            <div class="chips" style="margin-top:10px">
              <span class="chip">本地生活探索者</span>
              <span class="chip">已登录</span>
            </div>
          </div>
        </div>
        <div class="panel">
          <h3 class="panel-title">每日签到</h3>
          <p class="panel-sub">用 Redis Bitmap 记录你的坚持</p>
          <div class="sign-num"><span id="sign-num">—</span><small>天连续签到</small></div>
          <div class="sign-actions">
            <button class="btn btn-primary" id="sign-btn">今日签到</button>
            <button class="btn btn-ghost" data-act="logout">退出登录</button>
          </div>
        </div>
      </section>

      <section class="section">
        <div class="section-head">
          <div>
            <h2 class="section-title">我的笔记</h2>
            <p class="section-sub">你分享过的探店经历</p>
          </div>
        </div>
        <div class="grid-blogs" id="my-blogs">${skeletonCards(2)}</div>
      </section>`;

    document.getElementById('sign-btn').addEventListener('click', signToday);

    api('/user/sign/count')
      .then((count) => {
        const el = document.getElementById('sign-num');
        if (el) el.textContent = String(count || 0);
      })
      .catch(() => {});

    try {
      const blogs = await api('/blog/of/me?current=1');
      const box = document.getElementById('my-blogs');
      if (box) box.innerHTML = blogs && blogs.length ? blogs.map(blogCard).join('') : emptyState('还没有写过笔记', '📝');
    } catch (e) {
      const box = document.getElementById('my-blogs');
      if (box) box.innerHTML = emptyState('暂时无法加载', '⚠️');
    }
  }

  async function signToday() {
    const button = document.getElementById('sign-btn');
    button.disabled = true;
    try {
      await api('/user/sign', { method: 'POST' });
      const count = await api('/user/sign/count').catch(() => null);
      document.getElementById('sign-num').textContent = String(count || 0);
      toast('签到成功，明天也要来哦');
    } catch (e) {
      handleError(e);
    } finally {
      button.disabled = false;
    }
  }

  /* ---------------------------- 路由 ---------------------------- */

  function setNav(name) {
    document.querySelectorAll('[data-nav]').forEach((el) => {
      el.classList.toggle('active', el.dataset.nav === name);
    });
  }

  function route() {
    clearInterval(countdownTimer);
    const hash = location.hash.replace(/^#/, '') || '/';
    const [pathPart, queryPart] = hash.split('?');
    const parts = pathPart.split('/').filter(Boolean);
    const query = new URLSearchParams(queryPart || '');

    setNav(parts[0] === 'me' ? 'me' : 'home');
    window.scrollTo({ top: 0, behavior: 'instant' in window ? 'instant' : 'auto' });

    if (parts[0] === 'shop' && parts[1]) return renderShop(parts[1]);
    if (parts[0] === 'blog' && parts[1]) return renderBlog(parts[1]);
    if (parts[0] === 'me') return renderMe();
    if (parts[0] === 'search') return renderSearch(query.get('q') || '');
    return renderHome();
  }

  /* ---------------------------- 全局事件 ---------------------------- */

  document.addEventListener('click', (event) => {
    const target = event.target.closest('[data-act]');
    if (!target) {
      if (event.target.classList && event.target.classList.contains('modal-overlay')) closeLogin();
      return;
    }

    const act = target.dataset.act;
    if (act === 'login') {
      event.preventDefault();
      openLogin();
    } else if (act === 'close-modal') {
      if (event.target === target) closeLogin();
    } else if (act === 'logout') {
      logout();
    } else if (act === 'switch-type') {
      document.querySelectorAll('.cat').forEach((el) => el.classList.remove('active'));
      target.classList.add('active');
      loadShops(target.dataset.type, target.dataset.name);
    } else if (act === 'seckill') {
      seckill(target.dataset.voucher, target);
    }
  });

  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') closeLogin();
  });

  window.addEventListener('hashchange', route);

  /* ---------------------------- 启动 ---------------------------- */

  renderTopbar();
  ensureUser().finally(route);
})();
