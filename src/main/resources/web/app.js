    let token = localStorage.getItem('nn_token') || '';
    let state = null, tab = 'overview', events = null;
    let commandFilter = '', commandCategory = 'all', rankFilter = '';
    const collapsedCommandGroups = new Set();
    let sessionTotalMs = 15*60*1000, sessionRemainingMs = 0, sessionTimer = null, sessionExtendShown = false;

    const colors = [
      ['0','Negro','#000000'], ['1','Azul oscuro','#0000aa'], ['2','Verde oscuro','#00aa00'], ['3','Aqua oscuro','#00aaaa'],
      ['4','Rojo oscuro','#aa0000'], ['5','Morado','#aa00aa'], ['6','Dorado','#ffaa00'], ['7','Gris','#aaaaaa'],
      ['8','Gris oscuro','#555555'], ['9','Azul','#5555ff'], ['a','Verde','#55ff55'], ['b','Aqua','#55ffff'],
      ['c','Rojo','#ff5555'], ['d','Rosa','#ff55ff'], ['e','Amarillo','#ffff55'], ['f','Blanco','#ffffff']
    ];
    const formats = [['l','B'], ['o','I'], ['n','U'], ['m','S'], ['r','Reset']];
    const I_users='<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/></svg>';
    const I_bolt='<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></svg>';
    const I_star='<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z"/></svg>';
    const I_cmd='<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"><polyline points="4 17 10 11 4 5"/><line x1="12" y1="19" x2="20" y2="19"/></svg>';
    const I_userPlus='<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"><path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><line x1="20" y1="8" x2="20" y2="14"/><line x1="23" y1="11" x2="17" y2="11"/></svg>';
    const I_layers='<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"><polygon points="12 2 2 7 12 12 22 7 12 2"/><polyline points="2 17 12 22 22 17"/><polyline points="2 12 12 17 22 12"/></svg>';
    const I_tab='<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="4" width="18" height="16" rx="2"/><line x1="3" y1="9" x2="21" y2="9"/></svg>';
    const I_sun='<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M6.34 17.66l-1.41 1.41M19.07 4.93l-1.41 1.41"/></svg>';
    const I_moon='<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/></svg>';
    function applyTheme(t){ document.documentElement.setAttribute('data-theme', t); try{ localStorage.setItem('nn_theme', t); }catch(e){} const b=document.getElementById('themeBtn'); if(b) b.innerHTML = (t==='dark'? I_sun : I_moon); }
    function toggleTheme(){ applyTheme(document.documentElement.getAttribute('data-theme')==='dark' ? 'light' : 'dark'); }
    applyTheme((function(){ try{ return localStorage.getItem('nn_theme'); }catch(e){ return null; } })() || 'light');
    function statCard(label, num, sub, color, icon){
      return `<div class="statCard"><div class="statTop"><span class="label">${label}</span><span class="statIcon" style="background:${color}1f;color:${color}">${icon}</span></div><div class="statNum">${num}</div><div class="statSub">${escapeHtml(String(sub))}</div></div>`;
    }
    function tile(name, sub, onclick, color, icon){
      return `<button class="tile" onclick="${onclick}"><span class="tileIcon" style="background:linear-gradient(135deg,${color},${color}cc)">${icon}</span><span style="flex:1;min-width:0"><div class="t-name">${name}</div><div class="t-sub">${sub}</div></span></button>`;
    }
    function rankTableRow(r){
      return `<tr><td><div class="cellUser"><div><div class="uName">${escapeHtml(r.displayName||r.name)}</div><div class="uHandle">${escapeHtml(r.name)}</div></div></div></td><td><span class="pill">peso ${r.weight||0}</span></td><td><span style="display:inline-block;background:#1e1b2e;color:#fff;padding:4px 9px;border-radius:7px;font-family:var(--mono);font-size:12px">${prefixHtml(r.prefix||'&7['+r.name+']')}</span></td><td><span class="muted">${(r.permissions||[]).length} reglas</span></td><td class="right"><div class="tableActions"><button onclick="editRankByName('${escapeAttr(r.name)}')">Editar</button><button class="danger" onclick="deleteRank('${escapeAttr(r.name)}')">Eliminar</button></div></td></tr>`;
    }
    if (token) boot();

    async function login() {
      try {
        const res = await fetch('/api/login', { method:'POST', body: JSON.stringify({ password:document.getElementById('password').value }) });
        if (res.status === 404 || res.status === 501 || res.status === 405) { token = 'test'; localStorage.setItem('nn_token', token); bootDemo(); return; }
        const data = await res.json();
        if (!res.ok) { document.getElementById('loginError').textContent = data.error || 'Contraseña incorrecta'; return; }
        token = data.token; localStorage.setItem('nn_token', token);
        if (data.remainingMs) { sessionRemainingMs = data.remainingMs; sessionTotalMs = data.remainingMs; }
        boot();
      } catch(e) {
        token = 'test'; localStorage.setItem('nn_token', token); bootDemo();
      }
    }
    function bootDemo() {
      document.getElementById('login').classList.add('hidden');
      document.getElementById('app').classList.remove('hidden');
      if (localStorage.getItem('nn_collapsed') === '1') document.getElementById('app').classList.add('collapsed');
      state = {
        users: { 'abc-123': { uuid:'abc-123', name:'Steve', online:true, ranks:['admin'], primaryRank:'admin', permissions:[], meta:{} }, 'def-456': { uuid:'def-456', name:'Alex', online:false, ranks:['default'], primaryRank:'default', permissions:[], meta:{} }, 'ghi-789': { uuid:'ghi-789', name:'Notch', online:true, ranks:['vip','admin'], primaryRank:'admin', permissions:[{node:'command.tp',value:true,mode:'literal',contexts:{}}], meta:{} } },
        ranks: { admin: { name:'admin', displayName:'Admin', prefix:'&c[Admin] ', weight:100, parents:[], permissions:[{node:'command.gamemode',value:true,mode:'literal',contexts:{}},{node:'command.tp',value:true,mode:'literal',contexts:{}}], meta:{gradientStart:'#ff3d8b',gradientMiddle:'#7c4dff',gradientEnd:'#00e5ff'} }, vip: { name:'vip', displayName:'VIP', prefix:'&a[VIP] ', weight:50, parents:['default'], permissions:[{node:'command.fly',value:true,mode:'literal',contexts:{}}], meta:{gradientStart:'#00e676',gradientMiddle:'#00e5ff',gradientEnd:'#7c4dff'} }, default: { name:'default', displayName:'Jugador', prefix:'&7[Jugador] ', weight:0, parents:[], permissions:[], meta:{} } },
        commands: [ {name:'gamemode',permissionNode:'command.gamemode',source:'Minecraft Vanilla',paths:['gamemode survival','gamemode creative']}, {name:'tp',permissionNode:'command.tp',source:'Minecraft Vanilla',paths:['tp','teleport']}, {name:'fly',permissionNode:'command.fly',source:'Essentials',paths:['fly']}, {name:'heal',permissionNode:'command.heal',source:'Essentials',paths:['heal']}, {name:'give',permissionNode:'command.give',source:'Minecraft Vanilla',paths:['give']}, {name:'kick',permissionNode:'command.kick',source:'Minecraft Vanilla',paths:['kick']}, {name:'ban',permissionNode:'command.ban',source:'Minecraft Vanilla',paths:['ban']}, {name:'home',permissionNode:'command.home',source:'Essentials',paths:['home','sethome']} ],
        defaultRank: 'default',
        tabSettings: { showPing: true }
      };
      render(); setStatus('Demo (sin servidor)');
    }
    async function boot() {
      document.getElementById('login').classList.add('hidden');
      document.getElementById('app').classList.remove('hidden');
      if (localStorage.getItem('nn_collapsed') === '1') document.getElementById('app').classList.add('collapsed');
      await load();
      try {
        const sess = await api('/api/session');
        if (sess.remainingMs) { sessionRemainingMs = sess.remainingMs; sessionTotalMs = sess.totalMs || sess.remainingMs; }
      } catch(e) {}
      startSessionTimer();
      if (events) events.close();
      events = new EventSource('/api/events?token=' + encodeURIComponent(token));
      events.addEventListener('changed', load);
      events.addEventListener('ready', () => setStatus('En vivo'));
      events.addEventListener('session_expired', () => showSessionExpired());
      events.onerror = () => setStatus('Reconectando...');
    }
    async function api(path, options = {}) {
      options.headers = Object.assign({ Authorization:'Bearer ' + token }, options.headers || {});
      if (options.body && typeof options.body !== 'string') { options.headers['Content-Type']='application/json'; options.body=JSON.stringify(options.body); }
      const res = await fetch(path, options);
      if (res.status === 401) { localStorage.removeItem('nn_token'); location.reload(); }
      if (!res.ok) throw new Error((await res.json()).error || res.statusText);
      return res.headers.get('content-type')?.includes('json') ? res.json() : res.text();
    }
    async function load() { state = await api('/api/state'); render(); setStatus('Sincronizado'); }
    function toggleRail() { const app=document.getElementById('app'); app.classList.toggle('collapsed'); localStorage.setItem('nn_collapsed', app.classList.contains('collapsed') ? '1' : '0'); }
    function showTab(next) {
      tab = next;
      for (const name of ['overview','users','ranks','commands']) {
        document.getElementById(name + 'View').classList.toggle('hidden', name !== tab);
        document.getElementById('tab' + cap(name)).classList.toggle('active', name === tab);
      }
      const titles = {
        overview:['Resumen','Control general, actividad y accesos rápidos.'],
        users:['Jugadores','Gestiona rangos y permisos directos por usuario.'],
        ranks:['Rangos','Crea jerarquías, prefijos, gradientes y reglas.'],
        commands:['Comandos','Permite o bloquea comandos agrupados por mod.']
      };
      document.getElementById('pageTitle').textContent = titles[tab][0];
      document.getElementById('pageHint').textContent = titles[tab][1];
      render();
    }
    function render() {
      if (!state) return;
      const users=Object.values(state.users || {}), ranks=Object.values(state.ranks || {}), commands=state.commands || [];
      document.getElementById('navUsers').textContent=users.length;
      document.getElementById('navRanks').textContent=ranks.length;
      document.getElementById('navCommands').textContent=commands.length;
      renderOverview(users, ranks, commands); renderUsers(users); renderRanks(ranks); renderCommands(commands);
    }
    function renderOverview(users, ranks, commands) {
      const online=users.filter(u=>u.online).length;
      const rules=ranks.reduce((n,r)=>n+(r.permissions||[]).length,0)+users.reduce((n,u)=>n+(u.permissions||[]).length,0);
      const sortedRanks=[...ranks].sort((a,b)=>(b.weight||0)-(a.weight||0));
      document.getElementById('overviewView').innerHTML = `
        <div class="bento">
          ${statCard('Jugadores', users.length, online+' en línea ahora', '#6d5dfb', I_users)}
          ${statCard('En línea', online, users.length+' registrados', '#18c5a0', I_bolt)}
          ${statCard('Rangos', ranks.length, rules+' reglas activas', '#f5a623', I_star)}
          ${statCard('Comandos', commands.length, 'detectados en el servidor', '#4d7cfe', I_cmd)}
          <div class="actionsCard">
            <div class="cardTitle"><h2>Acciones rápidas</h2><span class="muted">Atajos del panel</span></div>
            <div class="tileGrid">
              ${tile('Crear rango','Peso, prefijo y herencia',"newRank()",'#6d5dfb', I_star)}
              ${tile('Configurar comandos','Permitir o bloquear',"showTab('commands')",'#18c5a0', I_cmd)}
              ${tile('Añadir jugador','Offline por UUID',"newUser()",'#f5a623', I_userPlus)}
              ${tile('Gestionar rangos','Editar jerarquías',"showTab('ranks')",'#4d7cfe', I_layers)}
            </div>
          </div>
          <div class="tabCard">
            <div class="cardTitle"><h2>Lista TAB</h2></div>
            <div class="tabRow"><span class="tileIcon">${I_tab}</span><div style="flex:1;min-width:0"><div class="t-name">Mostrar ping</div><div class="t-sub">Ping como texto de color junto al nombre en el TAB del juego</div></div><label class="miniCheck" style="margin:0"><input type="checkbox" id="tabShowPing" ${(state.tabSettings||{}).showPing !== false ? 'checked' : ''} onchange="saveTabSettings()"></label></div>
          </div>
          <div class="tableCard">
            <div class="cardHead"><h2>Rangos del servidor</h2><button class="primary" onclick="newRank()">+ Nuevo rango</button></div>
            <table class="dataTable"><thead><tr><th>Rango</th><th>Peso</th><th>Prefijo</th><th>Reglas</th><th></th></tr></thead><tbody>${sortedRanks.map(rankTableRow).join('') || '<tr><td colspan="5"><div class="empty">No hay rangos definidos.</div></td></tr>'}</tbody></table>
          </div>
        </div>`;
    }
    async function saveTabSettings() {
      const showPing = document.getElementById('tabShowPing')?.checked ?? true;
      try {
        await api('/api/settings/tab', { method: 'PUT', body: { showPing } });
        toast('Configuración del TAB guardada');
      } catch(e) { toast('Error: ' + e.message); }
    }
    function renderUsers(users) {
      const filter=(document.getElementById('userFilter')?.value || '').toLowerCase();
      const filtered=users.filter(u=>(u.name+u.uuid+(u.ranks||[]).join(' ')).toLowerCase().includes(filter));
      document.getElementById('usersView').innerHTML = `
        <div class="toolbar"><input id="userFilter" placeholder="Buscar jugador, UUID o rango" value="${escapeAttr(filter)}" oninput="render()"><button class="primary" onclick="newUser()">+ Jugador offline</button><button class="ghost" onclick="load()">Actualizar</button></div>
        <div class="tableCard"><div class="cardHead"><h2>Jugadores</h2><span class="muted">${filtered.length} visibles</span></div>
          <table class="dataTable"><thead><tr><th>Jugador</th><th>Rango principal</th><th>Rangos</th><th>Estado</th><th></th></tr></thead><tbody>${filtered.map(userRow).join('') || '<tr><td colspan="5"><div class="empty">Todavía no hay jugadores registrados.</div></td></tr>'}</tbody></table></div>`;
    }
    function userRow(u) {
      return `<tr><td><div class="cellUser"><img class="avatar" src="${headUrl(u)}" onerror="this.src='https://mc-heads.net/avatar/Steve/64'" alt=""><div><div class="uName">${escapeHtml(u.name || 'Offline')}</div><div class="uHandle">${escapeHtml(u.uuid)}</div></div></div></td>
        <td><span class="rankBadge">${escapeHtml(rankLabel(u.primaryRank||''))}</span></td>
        <td><div class="chips">${(u.ranks||[]).map(r=>`<span class="rankBadge">${escapeHtml(rankLabel(r))}</span>`).join('') || '<span class="muted">—</span>'}</div></td>
        <td><span class="statusBadge ${u.online?'online':'off'}"><span class="onlineDot ${u.online?'on':''}"></span>${u.online?'Online':'Offline'}</span></td>
        <td class="right"><div class="tableActions"><button onclick="editUser('${escapeAttr(u.uuid)}')">Editar</button><button class="danger" onclick="deleteUser('${escapeAttr(u.uuid)}')">Eliminar</button></div></td></tr>`;
    }
    function renderRanks(ranks) {
      const q=(document.getElementById('rankSearch')?.value || rankFilter).toLowerCase(); rankFilter=q;
      const filtered=ranks.sort((a,b)=>(b.weight||0)-(a.weight||0)).filter(r=>(r.name+r.displayName+(r.parents||[]).join(' ')).toLowerCase().includes(q));
      document.getElementById('ranksView').innerHTML = `
        <div class="toolbar"><input id="rankSearch" placeholder="Buscar rango" value="${escapeAttr(q)}" oninput="rankFilter=this.value; renderRanks(Object.values(state.ranks || {}))"><button class="primary" onclick="newRank()">Nuevo rango</button><button onclick="load()">Actualizar</button></div>
        <div class="grid">${filtered.map(rankCard).join('') || '<div class="empty">No hay rangos para mostrar.</div>'}</div>`;
    }
    function rankCard(r) {
      const meta=r.meta || {}, start=meta.gradientStart || '#4ff0ff', mid=meta.gradientMiddle || '#9c6bff', end=meta.gradientEnd || '#ff4f9b';
      return `<div class="rankCard" style="--accent:${escapeAttr(start)};--accent2:${escapeAttr(mid)};--hot:${escapeAttr(end)}"><div class="row"><div><h2>${escapeHtml(r.displayName || r.name)}</h2><div class="muted">${escapeHtml(r.name)} &middot; peso ${r.weight || 0}</div></div><span class="pill">${(r.permissions||[]).length} reglas</span></div>
        <div class="prefixPreview">${prefixHtml(r.prefix || '&7[' + r.name + '] ')}</div>
        <div class="chips">${(r.parents||[]).map(p=>`<span class="rankBadge">hereda ${escapeHtml(rankLabel(p))}</span>`).join('') || '<span class="muted">Sin herencia</span>'}</div>
        <div class="row"><button onclick="editRankByName('${escapeAttr(r.name)}')">Editar</button><button class="danger" onclick="deleteRank('${escapeAttr(r.name)}')">Eliminar</button></div></div>`;
    }
    function renderCommands(commands) {
      const q=(document.getElementById('commandSearch')?.value ?? commandFilter).toLowerCase(); commandFilter=q;
      const ranks=Object.values(state.ranks || {}).sort((a,b)=>(b.weight||0)-(a.weight||0));
      const rankName=document.getElementById('commandRank')?.value || ranks[0]?.name || '';
      const rank=state.ranks?.[rankName] || ranks[0] || { permissions:[] };
      const categories=['all', ...Array.from(new Set(commands.map(commandSource))).sort((a,b)=>a.localeCompare(b))];
      commandCategory=document.getElementById('commandCategory')?.value || commandCategory || 'all';
      if (!categories.includes(commandCategory)) commandCategory='all';
      const visible=commands.filter(c => commandMatches(c,q) && (commandCategory==='all' || commandSource(c)===commandCategory));
      const groups=groupBy(visible, commandSource);
      document.getElementById('commandsView').innerHTML = `
        <div class="toolbar commands"><input id="commandSearch" placeholder="Buscar comando, permiso o subcomando" value="${escapeAttr(q)}" oninput="commandFilter=this.value; renderCommands(state.commands || [])">
          <select id="commandCategory" onchange="commandCategory=this.value; renderCommands(state.commands || [])">${categories.map(c=>`<option value="${escapeAttr(c)}" ${c===commandCategory?'selected':''}>${c==='all'?'Todos los mods':escapeHtml(c)}</option>`).join('')}</select>
          <select id="commandRank" onchange="renderCommands(state.commands || [])">${ranks.map(r=>`<option value="${escapeAttr(r.name)}" ${rank.name===r.name?'selected':''}>${escapeHtml(r.displayName || r.name)}</option>`).join('')}</select>
          <button onclick="editRankByName(document.getElementById('commandRank').value)">Editar rango</button></div>
        <div class="panel"><div class="panelHead"><div><h2>Reglas de ${escapeHtml(rank.displayName || rank.name || 'rango')}</h2><div class="muted">Estos botones aplican a todos los comandos detectados del servidor.</div></div><div class="heroActions"><button class="good" onclick="setRankCommands('${escapeAttr(rank.name)}',true)">Permitir todos</button><button class="bad" onclick="setRankCommands('${escapeAttr(rank.name)}',false)">Bloquear todos</button><button onclick="setRankCommands('${escapeAttr(rank.name)}',null)">Reiniciar todos</button></div></div></div>
        <div class="commandGroups">${Object.entries(groups).map(([source,items])=>commandGroup(source,items,rank)).join('') || '<div class="empty">No hay comandos con ese filtro.</div>'}</div>`;
    }
    function commandGroup(source, items, rank) {
      const key='main:'+source, collapsed=collapsedCommandGroups.has(key);
      return `<div class="commandGroup ${collapsed?'collapsed':''}" data-group-key="${escapeAttr(key)}"><div class="panelHead"><div><h2>${escapeHtml(source)}</h2><div class="muted">${items.length} comandos encontrados</div></div><div class="heroActions"><span class="sourcePill">${escapeHtml(source)}</span><button class="icon collapseCommand" title="${collapsed?'Expandir categoría':'Colapsar categoría'}" onclick="toggleCommandGroup(this.closest('.commandGroup'))">${collapsed?'+':'-'}</button></div></div><div class="commandGrid">${items.map(c=>commandControl(c,rank)).join('')}</div></div>`;
    }
    function commandControl(command, owner) {
      const status=commandStatus(owner, command.permissionNode);
      return `<div class="commandRow"><div class="row"><b>/${escapeHtml(command.name)}</b><span class="pill">${escapeHtml(status.label)}</span></div><span class="sourcePill">${escapeHtml(commandSource(command))}</span><div class="muted">${escapeHtml(command.permissionNode)}</div>${commandPaths(command)}
        <div class="seg"><button class="${status.value===true?'activeAllow':''}" onclick="setOwnerCommand('rank','${escapeAttr(owner.name)}','${escapeAttr(command.permissionNode)}',true)">Permitir</button><button class="${status.value===false?'activeDeny':''}" onclick="setOwnerCommand('rank','${escapeAttr(owner.name)}','${escapeAttr(command.permissionNode)}',false)">Bloquear</button><button class="${status.value===null?'activeUnset':''}" onclick="setOwnerCommand('rank','${escapeAttr(owner.name)}','${escapeAttr(command.permissionNode)}',null)">Normal</button></div></div>`;
    }
    async function setRankCommands(rankName, value) {
      const rank=clone(state.ranks[rankName]); const nodes=(state.commands||[]).map(c=>c.permissionNode);
      rank.permissions=(rank.permissions||[]).filter(p=>!nodes.includes(p.node));
      if (value!==null) nodes.forEach(node=>rank.permissions.push({node,value,mode:'literal',contexts:{}}));
      await api('/api/ranks/'+encodeURIComponent(rank.name), {method:'PUT', body:rank}); toast('Comandos actualizados');
    }
    async function setOwnerCommand(type, id, node, value) {
      const owner=clone(type==='rank' ? state.ranks[id] : state.users[id]); owner.permissions=(owner.permissions||[]).filter(p=>p.node!==node);
      if (value!==null) owner.permissions.push({node,value,mode:'literal',contexts:{}});
      await api((type==='rank'?'/api/ranks/':'/api/users/')+encodeURIComponent(type==='rank'?owner.name:owner.uuid), {method:'PUT', body:owner}); toast('Regla guardada');
    }
    function commandStatus(owner,node) { const r=(owner.permissions||[]).find(p=>p.node===node); return r ? {value:!!r.value,label:r.value?'Permitido':'Bloqueado'} : {value:null,label:'Normal'}; }
    function commandPaths(command) { const paths=(command.paths||[]).filter(p=>p && p!==command.name).slice(0,6); return paths.length ? `<div class="commandPaths">${paths.map(p=>`<span>/${escapeHtml(p.replaceAll('.',' '))}</span>`).join('')}</div>` : ''; }
    function commandSource(c) { return c.source || 'Minecraft Vanilla'; }
    function commandMatches(c,q) { return !q || (c.name+c.permissionNode+(c.vanillaPermissionNode||'')+(c.paths||[]).join(' ')+commandSource(c)).toLowerCase().includes(q); }
    function groupBy(items, fn) { return items.reduce((out,item)=>{ const key=fn(item); (out[key] ||= []).push(item); return out; }, {}); }
    function toggleCommandGroup(group) { if (!group) return; const key=group.dataset.groupKey || ''; const collapsed=!group.classList.contains('collapsed'); group.classList.toggle('collapsed', collapsed); if (collapsed) collapsedCommandGroups.add(key); else collapsedCommandGroups.delete(key); const button=group.querySelector('.collapseCommand'); if (button) { button.textContent=collapsed?'+':'-'; button.title=collapsed?'Expandir categoría':'Colapsar categoría'; } }
    function openModal(kind, data) {
      const isRank=kind==='rank', wrap=document.createElement('div'); wrap.className='modalBackdrop';
      wrap.innerHTML=`<div class="modal"><div class="modalHead"><div class="brandLine"><div class="logoMark"><svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2">${isRank?'<path d="M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z"/>':'<path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/>'}</svg></div><div><h2>${isRank?'Editor de rango':'Editor de jugador'}</h2><div class="muted">${isRank?'Configura identidad, prefijo, herencia y comandos.':'Gestiona rangos asignados y permisos directos.'}</div></div></div><button class="icon" data-close>X</button></div><div class="modalBody">${isRank?rankEditor(data):userEditor(data)}</div><div class="modalFoot"><button data-close>Cancelar</button><button class="primary" data-save><svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="20 6 9 17 4 12"/></svg> Guardar cambios</button></div></div>`;
      document.body.appendChild(wrap); wrap.querySelectorAll('[data-close]').forEach(b=>b.onclick=()=>wrap.remove());
      if (isRank) { syncPrefixPreview(); syncSuffixPreview(); renderModalCommands(); } else renderUserModalCommands();
      wrap.querySelector('[data-save]').onclick=async()=>{ const updated=isRank?readRankEditor():readUserEditor(data); await api((isRank?'/api/ranks/':'/api/users/')+encodeURIComponent(isRank?updated.name:updated.uuid), {method:'PUT', body:updated}); wrap.remove(); toast('Cambios guardados'); };
    }
    function rankEditor(r) {
      const meta=r.meta || {}, gs=meta.gradientStart || '#4ff0ff', gm=meta.gradientMiddle || '#9c6bff', ge=meta.gradientEnd || '#ff4f9b';
      const rankOptions=Object.values(state.ranks||{}).filter(x=>x.name!==r.name).map(x=>`<option value="${escapeAttr(x.name)}">${escapeHtml(x.displayName||x.name)}</option>`).join('');
      return `<div class="editorLayout">
        <div class="editorSection"><div class="sectionHeader"><h3><span class="sectionIcon"><svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg></span> Identidad del rango</h3></div>
          <div class="sectionBody"><div class="editorFields cols-4"><label>ID único<input id="rank_id" value="${escapeAttr(r.name)}" placeholder="admin"></label><label>Nombre visible<input id="rank_name" value="${escapeAttr(r.displayName||r.name)}" placeholder="Administrador"></label><label>Peso (prioridad)<input id="rank_weight" type="number" value="${escapeAttr(r.weight||0)}"></label><label>Herencia<input id="rank_parents" list="rankOptions" value="${escapeAttr((r.parents||[]).join(', '))}" placeholder="moderador, vip"></label></div><datalist id="rankOptions">${rankOptions}</datalist></div></div>
        <div class="editorSection"><div class="sectionHeader"><h3><span class="sectionIcon"><svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2L2 7l10 5 10-5-10-5z"/><path d="M2 17l10 5 10-5"/><path d="M2 12l10 5 10-5"/></svg></span> Prefijo y gradiente</h3><span class="prefixPreview" id="prefixPreview"></span></div>
          <div class="sectionBody"><div class="prefixLive"><span class="prefixLabel">Vista previa</span><span class="prefixPreview" id="prefixPreview2"></span></div><label>Texto con códigos de color (&amp; o hex &amp;#RRGGBB)<input id="rank_prefix" value="${escapeAttr(r.prefix||'')}" oninput="syncPrefixPreview()"></label>
            <label>Sufijo (después del nombre)<input id="rank_suffix" value="${escapeAttr(r.suffix||'')}" oninput="syncSuffixPreview()"></label>
            <div class="prefixLive"><span class="prefixLabel">Sufijo</span><span class="prefixPreview" id="suffixPreview"></span></div>
            <div class="gradientRow"><label>Inicio<input id="rank_grad_a" type="color" value="${escapeAttr(gs)}"></label><label>Medio<input id="rank_grad_m" type="color" value="${escapeAttr(gm)}"></label><label>Final<input id="rank_grad_b" type="color" value="${escapeAttr(ge)}"></label><label>Texto del gradiente<input id="rank_grad_text" value="${escapeAttr(r.displayName ? '[' + r.displayName + '] ' : '[' + r.name + '] ')}"></label><button class="primary" onclick="applyGradientPrefix()">Aplicar gradiente</button></div>
            <div class="row"><label class="miniCheck"><input id="rank_grad_l" type="checkbox">Negrita</label><label class="miniCheck"><input id="rank_grad_o" type="checkbox">Itálica</label><label class="miniCheck"><input id="rank_grad_n" type="checkbox">Subrayado</label><label class="miniCheck"><input id="rank_grad_mf" type="checkbox">Tachado</label></div>
            <div class="formatRow">${colors.map(c=>`<button class="swatch" style="background:${c[2]}" title="${c[1]}" onclick="insertPrefix('&${c[0]}')"></button>`).join('')}${formats.map(f=>`<button class="icon" title="${f[1]}" onclick="insertPrefix('&${f[0]}')">${f[1]}</button>`).join('')}</div></div></div>
        <input type="hidden" id="rank_command_rules_json" value="${escapeAttr(JSON.stringify((r.permissions||[]).filter(isCommandRule)))}">
        <div class="editorSection"><div class="sectionHeader"><h3><span class="sectionIcon"><svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><polyline points="4 17 10 11 4 5"/><line x1="12" y1="19" x2="20" y2="19"/></svg></span> Comandos del servidor</h3><span class="muted">${(state.commands||[]).length} detectados</span></div>
          <div class="sectionBody"><div class="toolbar"><input id="modalCommandSearch" placeholder="Filtrar comandos..." oninput="renderModalCommands()"><button class="good" onclick="setModalVisibleCommands(true)">Permitir todos</button><button class="bad" onclick="setModalVisibleCommands(false)">Bloquear todos</button><button onclick="setModalVisibleCommands(null)">Reiniciar</button></div><div class="modalCommandGroups" id="modalCommands"></div></div></div>
        <div class="editorSection"><div class="sectionHeader"><h3><span class="sectionIcon"><svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg></span> Permisos personalizados</h3><button onclick="addRule('rank')">+ Agregar regla</button></div>
          <div class="sectionBody"><div class="stack" id="rank_rules">${(r.permissions||[]).filter(p=>!isCommandRule(p)).map(ruleHtml).join('') || '<div class="empty">Sin permisos personalizados. Usa el botón para agregar reglas.</div>'}</div></div></div>
        <div class="editorSection"><div class="sectionHeader"><h3><span class="sectionIcon"><svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg></span> Datos avanzados (Meta)</h3></div>
          <div class="sectionBody"><label>JSON de metadatos<textarea id="rank_meta">${escapeHtml(JSON.stringify(r.meta||{},null,2))}</textarea></label></div></div>
      </div>`;
    }
    function userEditor(u) {
      const options=Object.values(state.ranks||{}).map(r=>`<option value="${escapeAttr(r.name)}">${escapeHtml(r.displayName||r.name)}</option>`).join('');
      return `<div class="editorLayout">
        <div class="playerHeader"><img class="avatar" src="${headUrl(u)}" onerror="this.src='https://mc-heads.net/avatar/Steve/64'" alt=""><div class="playerInfo"><h2>${escapeHtml(u.name||'Offline')}</h2><span class="uuid">${escapeHtml(u.uuid)}</span></div><div class="playerStatus"><span class="onlineDot ${u.online?'on':''}"></span>${u.online?'Online':'Offline'}</div></div>
        <div class="editorSection"><div class="sectionHeader"><h3><span class="sectionIcon"><svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg></span> Información del jugador</h3></div>
          <div class="sectionBody"><div class="editorFields cols-2"><label>Nombre<input id="user_name" value="${escapeAttr(u.name||'')}"></label><label>UUID<input id="user_uuid" value="${escapeAttr(u.uuid)}" readonly></label></div></div></div>
        <div class="editorSection"><div class="sectionHeader"><h3><span class="sectionIcon"><svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z"/></svg></span> Rangos asignados</h3></div>
          <div class="sectionBody"><div class="editorFields cols-2"><label>Rango primario<select id="user_primary">${Object.values(state.ranks||{}).map(r=>`<option value="${escapeAttr(r.name)}" ${u.primaryRank===r.name?'selected':''}>${escapeHtml(r.displayName||r.name)}</option>`).join('')}</select></label><label>Rangos adicionales (separados por coma)<input id="user_ranks" list="userRankOptions" value="${escapeAttr((u.ranks||[]).join(', '))}"></label></div><datalist id="userRankOptions">${options}</datalist><div class="editorFields cols-2"><label>Tag personal (después del sufijo)<input id="user_tag" value="${escapeAttr(u.tag||'')}" placeholder="[pepsi]"></label></div></div></div>
        <input type="hidden" id="user_command_rules_json" value="${escapeAttr(JSON.stringify((u.permissions||[]).filter(isCommandRule)))}">
        <div class="editorSection"><div class="sectionHeader"><h3><span class="sectionIcon"><svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><polyline points="4 17 10 11 4 5"/><line x1="12" y1="19" x2="20" y2="19"/></svg></span> Comandos directos</h3><span class="muted">${(state.commands||[]).length} detectados</span></div>
          <div class="sectionBody"><div class="toolbar"><input id="userModalCommandSearch" placeholder="Filtrar comandos..." oninput="renderUserModalCommands()"><button class="good" onclick="setUserModalVisibleCommands(true)">Permitir todos</button><button class="bad" onclick="setUserModalVisibleCommands(false)">Bloquear todos</button><button onclick="setUserModalVisibleCommands(null)">Reiniciar</button></div><div class="modalCommandGroups" id="userModalCommands"></div></div></div>
        <div class="editorSection"><div class="sectionHeader"><h3><span class="sectionIcon"><svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg></span> Permisos directos</h3><button onclick="addRule('user')">+ Agregar regla</button></div>
          <div class="sectionBody"><div class="stack" id="user_rules">${(u.permissions||[]).filter(p=>!isCommandRule(p)).map(ruleHtml).join('') || '<div class="empty">Sin permisos directos. Usa el botón para agregar reglas.</div>'}</div></div></div>
        <div class="editorSection"><div class="sectionHeader"><h3><span class="sectionIcon"><svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg></span> Datos avanzados (Meta)</h3></div>
          <div class="sectionBody"><label>JSON de metadatos<textarea id="user_meta">${escapeHtml(JSON.stringify(u.meta||{},null,2))}</textarea></label></div></div>
      </div>`;
    }
    function renderModalCommands() { const q=(document.getElementById('modalCommandSearch')?.value||'').toLowerCase(), box=document.getElementById('modalCommands'); if(!box)return; const groups=groupBy((state.commands||[]).filter(c=>commandMatches(c,q)), commandSource); box.innerHTML=Object.entries(groups).map(([source,items])=>modalCommandGroup(source,items,commandControlModal)).join('') || '<div class="empty">No hay comandos con ese filtro.</div>'; }
    function modalCommandGroup(source, items, renderer) { const key='modal:'+source, collapsed=collapsedCommandGroups.has(key); return `<div class="commandGroup ${collapsed?'collapsed':''}" data-group-key="${escapeAttr(key)}"><div class="panelHead"><div><h3>${escapeHtml(source)}</h3><div class="muted">${items.length} comandos</div></div><div class="heroActions"><span class="sourcePill">${escapeHtml(source)}</span><button class="icon collapseCommand" title="${collapsed?'Expandir categoría':'Colapsar categoría'}" onclick="toggleCommandGroup(this.closest('.commandGroup'))">${collapsed?'+':'-'}</button></div></div><div class="commandGrid">${items.map(renderer).join('')}</div></div>`; }
    function commandControlModal(c) { const s=commandStatus({permissions:modalCommandRules()}, c.permissionNode); return `<div class="commandRow"><div class="row"><b>/${escapeHtml(c.name)}</b><span class="pill">${escapeHtml(s.label)}</span></div><span class="sourcePill">${escapeHtml(commandSource(c))}</span>${commandPaths(c)}<div class="seg"><button class="${s.value===true?'activeAllow':''}" onclick="setModalCommand('${escapeAttr(c.permissionNode)}',true)">Permitir</button><button class="${s.value===false?'activeDeny':''}" onclick="setModalCommand('${escapeAttr(c.permissionNode)}',false)">Bloquear</button><button class="${s.value===null?'activeUnset':''}" onclick="setModalCommand('${escapeAttr(c.permissionNode)}',null)">Normal</button></div></div>`; }
    function setModalCommand(node,value){const input=document.getElementById('rank_command_rules_json'); let rules=modalCommandRules().filter(p=>p.node!==node); if(value!==null)rules.push({node,value,mode:'literal',contexts:{}}); input.value=JSON.stringify(rules); renderModalCommands();}
    function setModalVisibleCommands(value){const nodes=(state.commands||[]).map(c=>c.permissionNode), input=document.getElementById('rank_command_rules_json'); let rules=modalCommandRules().filter(p=>!nodes.includes(p.node)); if(value!==null)nodes.forEach(node=>rules.push({node,value,mode:'literal',contexts:{}})); input.value=JSON.stringify(rules); renderModalCommands();}
    function modalCommandRules(){try{return JSON.parse(document.getElementById('rank_command_rules_json')?.value||'[]')}catch{return[]}}
    function renderUserModalCommands(){const q=(document.getElementById('userModalCommandSearch')?.value||'').toLowerCase(), box=document.getElementById('userModalCommands'); if(!box)return; const groups=groupBy((state.commands||[]).filter(c=>commandMatches(c,q)), commandSource); box.innerHTML=Object.entries(groups).map(([source,items])=>modalCommandGroup(source,items,commandControlUserModal)).join('') || '<div class="empty">No hay comandos con ese filtro.</div>';}
    function commandControlUserModal(c){const s=commandStatus({permissions:userModalCommandRules()}, c.permissionNode); return `<div class="commandRow"><div class="row"><b>/${escapeHtml(c.name)}</b><span class="pill">${escapeHtml(s.label)}</span></div><span class="sourcePill">${escapeHtml(commandSource(c))}</span>${commandPaths(c)}<div class="seg"><button class="${s.value===true?'activeAllow':''}" onclick="setUserModalCommand('${escapeAttr(c.permissionNode)}',true)">Permitir</button><button class="${s.value===false?'activeDeny':''}" onclick="setUserModalCommand('${escapeAttr(c.permissionNode)}',false)">Bloquear</button><button class="${s.value===null?'activeUnset':''}" onclick="setUserModalCommand('${escapeAttr(c.permissionNode)}',null)">Normal</button></div></div>`;}
    function setUserModalCommand(node,value){const input=document.getElementById('user_command_rules_json'); let rules=userModalCommandRules().filter(p=>p.node!==node); if(value!==null)rules.push({node,value,mode:'literal',contexts:{}}); input.value=JSON.stringify(rules); renderUserModalCommands();}
    function setUserModalVisibleCommands(value){const nodes=(state.commands||[]).map(c=>c.permissionNode), input=document.getElementById('user_command_rules_json'); let rules=userModalCommandRules().filter(p=>!nodes.includes(p.node)); if(value!==null)nodes.forEach(node=>rules.push({node,value,mode:'literal',contexts:{}})); input.value=JSON.stringify(rules); renderUserModalCommands();}
    function userModalCommandRules(){try{return JSON.parse(document.getElementById('user_command_rules_json')?.value||'[]')}catch{return[]}}
    function insertPrefix(code){const input=document.getElementById('rank_prefix'); input.focus(); const s=input.selectionStart||input.value.length, e=input.selectionEnd||s; input.value=input.value.slice(0,s)+code+input.value.slice(e); input.selectionStart=input.selectionEnd=s+code.length; syncPrefixPreview();}
    function applyGradientPrefix(){const text=document.getElementById('rank_grad_text').value||'', a=document.getElementById('rank_grad_a').value, m=document.getElementById('rank_grad_m').value, b=document.getElementById('rank_grad_b').value, format=gradientFormatCodes(); document.getElementById('rank_prefix').value=gradientCode(text,a,m,b,format)+'&r '; syncPrefixPreview();}
    function gradientFormatCodes(){let codes=''; if(document.getElementById('rank_grad_l')?.checked) codes+='&l'; if(document.getElementById('rank_grad_o')?.checked) codes+='&o'; if(document.getElementById('rank_grad_n')?.checked) codes+='&n'; if(document.getElementById('rank_grad_mf')?.checked) codes+='&m'; return codes || leadingFormatCodes(document.getElementById('rank_prefix')?.value || '');}
    function leadingFormatCodes(value){const found=String(value||'').match(/^((?:&[lonm])*)/i); return found ? found[1] : '';}
    function gradientCode(text,a,m,b,format=''){const stops=[hexToRgb(a),hexToRgb(m),hexToRgb(b)], chars=[...text]; return chars.map((ch,i)=>{const t=chars.length<=1?0:i/(chars.length-1), segment=t<=.5?0:1, local=segment===0?t/.5:(t-.5)/.5, from=stops[segment], to=stops[segment+1]; const r=Math.round(from.r+(to.r-from.r)*local), g=Math.round(from.g+(to.g-from.g)*local), bl=Math.round(from.b+(to.b-from.b)*local); return '&#'+toHex(r)+toHex(g)+toHex(bl)+format+ch;}).join('');}
    function syncPrefixPreview(){const html=prefixHtml(document.getElementById('rank_prefix')?.value||''); const el=document.getElementById('prefixPreview'); if(el)el.innerHTML=html; const el2=document.getElementById('prefixPreview2'); if(el2)el2.innerHTML=html;}
    function syncSuffixPreview(){const el=document.getElementById('suffixPreview'); if(el)el.innerHTML=prefixHtml(document.getElementById('rank_suffix')?.value||'');}
    function ruleHtml(rule={node:'',value:true,mode:'literal',contexts:{}}){return `<div class="rule" data-rule><input data-node value="${escapeAttr(rule.node||'')}" placeholder="permiso.mod o mod.*"><select data-value><option value="true" ${rule.value?'selected':''}>Permitir</option><option value="false" ${!rule.value?'selected':''}>Bloquear</option></select><select data-mode><option ${rule.mode==='literal'?'selected':''}>literal</option><option ${rule.mode==='wildcard'?'selected':''}>wildcard</option><option ${rule.mode==='regex'?'selected':''}>regex</option></select><button class="icon bad" onclick="this.closest('[data-rule]').remove()">X</button></div>`;}
    function addRule(prefix){document.getElementById(prefix+'_rules').insertAdjacentHTML('beforeend', ruleHtml());}
    function readRankEditor(){const id=document.getElementById('rank_id').value.trim().toLowerCase(); const meta=readJsonBox('rank_meta'); meta.gradientStart=document.getElementById('rank_grad_a')?.value || meta.gradientStart; meta.gradientMiddle=document.getElementById('rank_grad_m')?.value || meta.gradientMiddle; meta.gradientEnd=document.getElementById('rank_grad_b')?.value || meta.gradientEnd; return {name:id, displayName:document.getElementById('rank_name').value, prefix:document.getElementById('rank_prefix').value, suffix:document.getElementById('rank_suffix').value, weight:Number(document.getElementById('rank_weight').value||0), parents:splitList(document.getElementById('rank_parents').value), permissions:[...modalCommandRules(), ...readRules('rank')], meta};}
    function readUserEditor(original){return {uuid:document.getElementById('user_uuid').value, name:document.getElementById('user_name').value, tag:document.getElementById('user_tag').value, primaryRank:document.getElementById('user_primary').value, ranks:splitList(document.getElementById('user_ranks').value), permissions:[...userModalCommandRules(), ...readRules('user')], meta:readJsonBox('user_meta'), lastSeen:original.lastSeen||Date.now(), online:!!original.online};}
    function readRules(prefix){return [...document.querySelectorAll(`#${prefix}_rules [data-rule]`)].map(row=>({node:row.querySelector('[data-node]').value.trim().toLowerCase(), value:row.querySelector('[data-value]').value==='true', mode:row.querySelector('[data-mode]').value, contexts:{}})).filter(r=>r.node);}
    function readJsonBox(id){try{return JSON.parse(document.getElementById(id).value||'{}')}catch{toast('Meta JSON inválido'); throw new Error('Meta JSON inválido')}}
    function newRank(){openModal('rank',{name:'nuevo',displayName:'Nuevo',prefix:'&7[Nuevo] ',suffix:'',weight:0,parents:[],permissions:[],meta:{gradientStart:'#4ff0ff',gradientMiddle:'#9c6bff',gradientEnd:'#ff4f9b'}})}
    function editRankByName(name){openModal('rank', clone(state.ranks[name]));}
    async function deleteRank(name){if(confirm('Eliminar rango '+name+'?')){await api('/api/ranks/'+encodeURIComponent(name),{method:'DELETE'}); toast('Rango eliminado');}}
    function editUser(uuid){openModal('user', clone(state.users[uuid]));}
    function newUser(){const uuid=prompt('UUID del jugador offline'); if(!uuid)return; openModal('user',{uuid,name:'Offline',tag:'',primaryRank:state.defaultRank||'default',ranks:[state.defaultRank||'default'],permissions:[],meta:{},online:false,lastSeen:Date.now()});}
    function isCommandRule(p){return p && (String(p.node||'').startsWith('command.') || String(p.node||'').startsWith('minecraft.command.'));}
    function rankLabel(name){const r=state.ranks?.[name]; return r ? (r.displayName || r.name) : name;}
    function headUrl(u){return u.uuid ? 'https://mc-heads.net/avatar/'+encodeURIComponent(u.uuid)+'/64' : 'https://mc-heads.net/avatar/Steve/64';}
    function splitList(value){return String(value||'').split(',').map(v=>v.trim().toLowerCase()).filter(Boolean);}
    function prefixHtml(text){const map=Object.fromEntries(colors.map(c=>[c[0],c[2]])); let style={color:'#fff',b:false,i:false,u:false,s:false}, out='', buf='', value=String(text||''); const flush=()=>{if(!buf)return; out+=`<span style="color:${style.color};font-weight:${style.b?'800':'700'};font-style:${style.i?'italic':'normal'};text-decoration:${style.u?'underline ':''}${style.s?'line-through':''}">${escapeHtml(buf)}</span>`; buf='';}; for(let i=0;i<value.length;i++){if(value[i]==='&'&&i+1<value.length){if(value[i+1]==='#'&&/^[0-9a-fA-F]{6}$/.test(value.slice(i+2,i+8))){flush(); style={...style,color:'#'+value.slice(i+2,i+8)}; i+=7; continue;} flush(); const c=value[++i].toLowerCase(); if(map[c]) style={...style,color:map[c]}; else if(c==='l')style.b=true; else if(c==='o')style.i=true; else if(c==='n')style.u=true; else if(c==='m')style.s=true; else if(c==='r')style={color:'#fff',b:false,i:false,u:false,s:false};} else buf+=value[i];} flush(); return out || '<span class="muted">Sin prefijo</span>';}
    function hexToRgb(hex){const n=parseInt(hex.replace('#',''),16); return {r:(n>>16)&255,g:(n>>8)&255,b:n&255};}
    function toHex(n){return n.toString(16).padStart(2,'0');}
    function startSessionTimer() {
      if (sessionTimer) clearInterval(sessionTimer);
      const info = document.getElementById('sessionInfo');
      if (info) info.style.display = '';
      sessionExtendShown = false;
      sessionTimer = setInterval(() => {
        sessionRemainingMs = Math.max(0, sessionRemainingMs - 1000);
        updateSessionBar();
        if (sessionRemainingMs <= 2*60*1000 && !sessionExtendShown) {
          sessionExtendShown = true;
          showExtendPrompt();
        }
      }, 1000);
      updateSessionBar();
    }
    function updateSessionBar() {
      const fill = document.getElementById('sessionBarFill');
      const text = document.getElementById('sessionText');
      if (!fill || !text) return;
      const pct = sessionTotalMs > 0 ? Math.max(0, sessionRemainingMs / sessionTotalMs * 100) : 0;
      fill.style.width = pct + '%';
      fill.style.background = pct < 15 ? 'var(--bad)' : pct < 35 ? 'var(--warn)' : 'var(--accent)';
      const mins = Math.floor(sessionRemainingMs / 60000);
      const secs = Math.floor((sessionRemainingMs % 60000) / 1000);
      text.textContent = mins + ':' + String(secs).padStart(2, '0');
    }
    function showExtendPrompt() {
      const wrap = document.createElement('div'); wrap.className = 'modalBackdrop sessionPrompt';
      wrap.innerHTML = `<div class="modal" style="max-width:420px"><div class="modalHead"><h2>Sesión por expirar</h2></div><div class="modalBody"><p>La sesión del panel se cerrará en menos de 2 minutos. ¿Deseas extender la sesión 15 minutos más?</p></div><div class="modalFoot"><button data-dismiss>Cerrar panel</button><button class="primary" data-extend>Extender sesión</button></div></div>`;
      document.body.appendChild(wrap);
      wrap.querySelector('[data-dismiss]').onclick = () => { wrap.remove(); };
      wrap.querySelector('[data-extend]').onclick = async () => {
        try {
          const res = await api('/api/extend', { method: 'POST' });
          sessionRemainingMs = res.remainingMs || 15*60*1000;
          sessionTotalMs = sessionRemainingMs;
          sessionExtendShown = false;
          updateSessionBar();
          toast('Sesión extendida');
        } catch(e) { toast('Error al extender: ' + e.message); }
        wrap.remove();
      };
    }
    function showSessionExpired() {
      if (sessionTimer) clearInterval(sessionTimer);
      sessionRemainingMs = 0; updateSessionBar();
      const wrap = document.createElement('div'); wrap.className = 'modalBackdrop sessionPrompt';
      wrap.innerHTML = `<div class="modal" style="max-width:420px"><div class="modalHead"><h2>Sesión expirada</h2></div><div class="modalBody"><p>La sesión del panel ha expirado. Para volver a acceder, ejecuta <code>/nn open web</code> en el servidor.</p></div><div class="modalFoot"><button class="primary" data-ok>Entendido</button></div></div>`;
      document.body.appendChild(wrap);
      wrap.querySelector('[data-ok]').onclick = () => { localStorage.removeItem('nn_token'); location.reload(); };
    }
    async function deleteUser(uuid) {
      if (!confirm('¿Eliminar al jugador ' + uuid + '? Esta acción no se puede deshacer.')) return;
      await api('/api/users/' + encodeURIComponent(uuid), { method: 'DELETE' });
      toast('Jugador eliminado');
    }
    function toast(text){const t=document.createElement('div'); t.className='toast'; t.textContent=text; document.body.appendChild(t); setTimeout(()=>t.remove(),2300);}
    function setStatus(text){const el=document.getElementById('status'); if(el)el.textContent=text;}
    function cap(s){return s[0].toUpperCase()+s.slice(1);}
    function clone(v){return JSON.parse(JSON.stringify(v));}
    function escapeHtml(value){return String(value ?? '').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));}
    function escapeAttr(value){return escapeHtml(value);}
