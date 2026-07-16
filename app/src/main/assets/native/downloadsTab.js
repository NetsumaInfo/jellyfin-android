// "Downloads" tab injected next to the home tabs (Home / Favorites).
// Renders downloads INSIDE the active web page (normal document flow):
//  - Home view: movies as cards + series grouped into folder cards (episode count badge)
//  - Series view: episodes sorted by season/episode, sizes, and a "Download more"
//    button that navigates to the original series page in the web UI
//  - Long-press multi-selection with delete; tapping a failed/incomplete item retries it
// Data + actions go through the native bridge (NativeInterface).
(function () {
    'use strict';

    var TAB_ID = 'nativeDownloadsTab';
    var ROOT_ID = 'nativeDownloadsRoot';
    var GRID_ID = 'nativeDownloadsGrid';
    var BAR_ID = 'nativeDownloadsBar';
    var SUB_ID = 'nativeDownloadsSub';
    var LONG_PRESS_MS = 500;

    var rootEl = null;
    var hiddenNodes = [];
    var pollTimer = null;
    var selectionMode = false;
    var selected = {};
    var view = { mode: 'home', seriesKey: null };

    function t(key, fallback) {
        try {
            if (window.Globalize && typeof window.Globalize.translate === 'function') {
                var s = window.Globalize.translate(key);
                if (s && s !== key) return s;
            }
        } catch (e) { /* ignore */ }
        return fallback;
    }

    function apiClient() {
        if (window.ApiClient) return window.ApiClient;
        try {
            if (window.ConnectionManager && window.ConnectionManager.currentApiClient) {
                return window.ConnectionManager.currentApiClient();
            }
        } catch (e) { /* ignore */ }
        return null;
    }

    function imageUrl(itemId) {
        try {
            var api = apiClient();
            if (api && api.getImageUrl) return api.getImageUrl(itemId, { type: 'Primary', maxWidth: 300 });
        } catch (e) { /* ignore */ }
        return null;
    }

    function fetchDownloads() {
        try { return JSON.parse(window.NativeInterface.getDownloads()); } catch (e) { return []; }
    }

    function videoPlayerType() {
        try { return window.NativeInterface.getVideoPlayerType(); } catch (e) { return 'exoplayer'; }
    }

    // Hand playback to the same player online playback uses.
    // The web player has no offline support, so it goes through jellyfin-web's own
    // playbackManager (which streams from the server); the other types read the local file.
    function playItem(d) {
        if (videoPlayerType() === 'webui') {
            try {
                var pm = window.NavigationHelper && window.NavigationHelper.playbackManager;
                var api = apiClient();
                // playbackManager rejects with "serverId required!" without it
                var serverId = api && typeof api.serverId === 'function' ? api.serverId() : null;
                if (pm && typeof pm.play === 'function' && serverId) {
                    hide();
                    pm.play({ ids: [d.itemId], serverId: serverId });
                    return;
                }
            } catch (e) { /* fall through to the native player */ }
        }
        try { window.NativeInterface.playDownload(d.itemId); } catch (e) { /* ignore */ }
    }

    function formatSize(bytes) {
        if (!bytes || bytes <= 0) return '';
        var units = ['o', 'Ko', 'Mo', 'Go', 'To'];
        var i = 0;
        var v = bytes;
        while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
        return (v >= 100 ? Math.round(v) : v.toFixed(1)) + ' ' + units[i];
    }

    // --- Grouping ----------------------------------------------------------

    function seriesKeyOf(d) {
        if (d.type === 'EPISODE' && (d.seriesId || d.seriesName)) {
            return d.seriesId || ('name:' + d.seriesName);
        }
        return null;
    }

    function groupItems(items) {
        var groups = {}; // key -> {key, seriesId, seriesName, episodes: []}
        var singles = [];
        items.forEach(function (d) {
            var key = seriesKeyOf(d);
            if (key) {
                if (!groups[key]) {
                    groups[key] = { key: key, seriesId: d.seriesId, seriesName: d.seriesName || '?', episodes: [] };
                }
                groups[key].episodes.push(d);
            } else {
                singles.push(d);
            }
        });
        Object.keys(groups).forEach(function (k) {
            groups[k].episodes.sort(function (a, b) {
                return (a.season - b.season) || (a.episode - b.episode);
            });
        });
        return { groups: groups, singles: singles };
    }

    // --- Navigation to web series page ("Download more") -------------------

    function openSeriesPage(seriesId) {
        if (!seriesId) return;
        hide();
        var api = apiClient();
        var serverId = null;
        try { serverId = api && api.serverId ? api.serverId() : null; } catch (e) { /* ignore */ }
        try {
            if (window.Emby && window.Emby.Page && typeof window.Emby.Page.showItem === 'function') {
                window.Emby.Page.showItem(seriesId, serverId);
                return;
            }
        } catch (e) { /* ignore */ }
        try {
            if (window.appRouter && typeof window.appRouter.showItem === 'function') {
                window.appRouter.showItem(seriesId, serverId);
                return;
            }
        } catch (e) { /* ignore */ }
        // Hash fallback (works on current jellyfin-web)
        var hash = '#/details?id=' + seriesId + (serverId ? '&serverId=' + serverId : '');
        window.location.hash = hash;
    }

    // --- Page host ---------------------------------------------------------

    function pageContainer() {
        return document.querySelector('.mainAnimatedPages .page:not(.hide)')
            || document.querySelector('.skinBody .page:not(.hide)')
            || document.querySelector('.homePage')
            || document.querySelector('.mainAnimatedPages')
            || document.body;
    }

    function isActive() {
        return rootEl != null && rootEl.parentNode != null;
    }

    // --- Status ------------------------------------------------------------

    function statusBadge(d) {
        if (d.status === 'DOWNLOADING') return d.percent >= 0 ? d.percent + '%' : t('Downloading', 'Téléchargement...');
        if (d.status === 'QUEUED') return t('LabelInProgress', 'En attente');
        if (d.status === 'ERROR') return t('Error', 'Erreur') + ' – ' + t('Retry', 'réessayer');
        if (!d.verified) return t('Incomplete', 'Incomplet') + ' – ' + t('Retry', 'réessayer');
        return null;
    }

    function isPlayable(d) {
        return d.status === 'DOWNLOADED' && d.verified;
    }

    function isRetryable(d) {
        return d.status === 'ERROR' || (d.status === 'DOWNLOADED' && !d.verified);
    }

    // --- Top bar -----------------------------------------------------------

    function renderBar(items) {
        var bar = document.getElementById(BAR_ID);
        if (!bar) return;
        bar.innerHTML = '';

        var left = document.createElement('button');
        left.type = 'button';
        left.style.cssText = 'background:none;border:none;color:#fff;font-size:22px;cursor:pointer;line-height:1;min-width:28px;';
        if (selectionMode) {
            left.textContent = '✕';
            left.addEventListener('click', exitSelection);
        } else if (view.mode === 'series') {
            left.textContent = '‹';
            left.addEventListener('click', function () { view = { mode: 'home', seriesKey: null }; render(); });
        } else {
            left.textContent = '';
        }

        var title = document.createElement('div');
        title.style.cssText = 'color:#fff;font-size:18px;font-weight:600;flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;';
        var count = Object.keys(selected).length;
        if (selectionMode) {
            title.textContent = count + ' ' + t('Selected', 'sélectionné(s)');
        } else if (view.mode === 'series') {
            var grouped = groupItems(items);
            var g = grouped.groups[view.seriesKey];
            title.textContent = g ? g.seriesName : t('Downloads', 'Téléchargements');
        } else {
            title.textContent = t('Downloads', 'Téléchargements');
        }

        bar.appendChild(left);
        bar.appendChild(title);

        if (selectionMode) {
            var del = document.createElement('button');
            del.type = 'button';
            del.style.cssText = 'background:none;border:none;color:#fff;font-size:20px;cursor:pointer;';
            del.textContent = '🗑';
            del.addEventListener('click', deleteSelected);
            bar.appendChild(del);
        }

        // Sub line: total used size (home) or "Download more" (series view)
        var sub = document.getElementById(SUB_ID);
        if (!sub) return;
        sub.innerHTML = '';
        if (!selectionMode && view.mode === 'home') {
            var total = 0;
            items.forEach(function (d) { total += d.size || 0; });
            if (total > 0) {
                var info = document.createElement('div');
                info.style.cssText = 'color:#aaa;font-size:12px;';
                info.textContent = t('LabelSize', 'Espace utilisé') + ' : ' + formatSize(total);
                sub.appendChild(info);
            }
        } else if (!selectionMode && view.mode === 'series') {
            var grouped2 = groupItems(items);
            var g2 = grouped2.groups[view.seriesKey];
            if (g2 && g2.seriesId) {
                var more = document.createElement('button');
                more.type = 'button';
                more.style.cssText = 'background:#00A4DC;border:none;border-radius:6px;color:#fff;' +
                    'font-size:14px;font-weight:600;padding:8px 14px;cursor:pointer;';
                more.textContent = '+ ' + t('Download', 'Télécharger') + ' ' + t('More', 'plus');
                more.addEventListener('click', function () { openSeriesPage(g2.seriesId); });
                sub.appendChild(more);
            }
        }
    }

    // --- Cards -------------------------------------------------------------

    function basePoster(imgId) {
        var poster = document.createElement('div');
        poster.style.cssText = 'position:relative;width:100%;padding-top:150%;border-radius:8px;overflow:hidden;background:#222;';
        var url = imgId ? imageUrl(imgId) : null;
        if (url) {
            var img = document.createElement('img');
            img.src = url;
            img.loading = 'lazy';
            img.style.cssText = 'position:absolute;top:0;left:0;width:100%;height:100%;object-fit:cover;';
            poster.appendChild(img);
        }
        return poster;
    }

    function addCheck(poster, isSel) {
        var check = document.createElement('div');
        check.textContent = isSel ? '✓' : '';
        check.style.cssText = 'position:absolute;top:6px;left:6px;width:24px;height:24px;border-radius:50%;' +
            'border:2px solid #fff;background:' + (isSel ? '#00A4DC' : 'rgba(0,0,0,0.4)') + ';' +
            'color:#fff;display:flex;align-items:center;justify-content:center;font-size:15px;z-index:2;';
        poster.appendChild(check);
    }

    function addTitle(card, text, subText) {
        var title = document.createElement('div');
        title.textContent = text;
        title.title = text;
        title.style.cssText = 'margin-top:6px;font-size:13px;color:#fff;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;';
        card.appendChild(title);
        if (subText) {
            var sub = document.createElement('div');
            sub.textContent = subText;
            sub.style.cssText = 'font-size:11px;color:#aaa;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;';
            card.appendChild(sub);
        }
    }

    function longPress(card, onLong) {
        var timer = null;
        var fired = { v: false };
        function start() {
            fired.v = false;
            timer = setTimeout(function () { fired.v = true; onLong(); }, LONG_PRESS_MS);
        }
        function cancel() { if (timer) { clearTimeout(timer); timer = null; } }
        card.addEventListener('touchstart', start, { passive: true });
        card.addEventListener('touchend', cancel);
        card.addEventListener('touchmove', cancel, { passive: true });
        card.addEventListener('mousedown', start);
        card.addEventListener('mouseup', cancel);
        card.addEventListener('mouseleave', cancel);
        card.addEventListener('contextmenu', function (e) { e.preventDefault(); onLong(); });
        return fired;
    }

    function makeItemCard(d, subTextOverride) {
        var card = document.createElement('div');
        card.style.cssText = 'position:relative;cursor:pointer;display:flex;flex-direction:column;';

        var poster = basePoster(d.itemId);
        var badge = statusBadge(d);
        if (badge) {
            var b = document.createElement('div');
            b.textContent = badge;
            b.style.cssText = 'position:absolute;left:0;right:0;bottom:0;background:rgba(0,0,0,0.72);color:#fff;font-size:12px;text-align:center;padding:4px;';
            poster.appendChild(b);
        } else {
            var play = document.createElement('div');
            play.textContent = '▶';
            play.style.cssText = 'position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);color:#fff;font-size:30px;text-shadow:0 0 6px #000;';
            poster.appendChild(play);
        }
        if (selectionMode) addCheck(poster, !!selected[d.id]);

        card.appendChild(poster);
        var sub = subTextOverride != null ? subTextOverride : formatSize(d.size);
        addTitle(card, d.name, sub);

        var fired = longPress(card, function () {
            if (!selectionMode) enterSelection();
            toggleSelect([d.id]);
        });

        card.addEventListener('click', function () {
            if (fired.v) { fired.v = false; return; }
            if (selectionMode) { toggleSelect([d.id]); return; }
            if (isPlayable(d)) {
                playItem(d);
            } else if (isRetryable(d)) {
                try { window.NativeInterface.retryDownload(d.id); } catch (e) { /* ignore */ }
                setTimeout(render, 300);
            }
        });

        return card;
    }

    function makeSeriesCard(group) {
        var card = document.createElement('div');
        card.style.cssText = 'position:relative;cursor:pointer;display:flex;flex-direction:column;';

        // Poster: use series image when we have the id, else first episode image
        var imgId = group.seriesId || (group.episodes[0] && group.episodes[0].itemId);
        var poster = basePoster(imgId);

        // Episode count badge
        var count = document.createElement('div');
        count.textContent = group.episodes.length;
        count.style.cssText = 'position:absolute;top:6px;right:6px;background:#00A4DC;color:#fff;' +
            'border-radius:12px;min-width:24px;height:24px;display:flex;align-items:center;' +
            'justify-content:center;font-size:13px;font-weight:600;padding:0 6px;z-index:2;';
        poster.appendChild(count);

        // Downloading indicator if any episode is active
        var active = group.episodes.some(function (d) { return d.status === 'DOWNLOADING' || d.status === 'QUEUED'; });
        if (active) {
            var b = document.createElement('div');
            b.textContent = t('Downloading', 'Téléchargement...');
            b.style.cssText = 'position:absolute;left:0;right:0;bottom:0;background:rgba(0,0,0,0.72);color:#fff;font-size:12px;text-align:center;padding:4px;';
            poster.appendChild(b);
        }

        var allIds = group.episodes.map(function (d) { return d.id; });
        if (selectionMode) {
            var allSel = allIds.every(function (id) { return !!selected[id]; });
            addCheck(poster, allSel);
        }

        card.appendChild(poster);
        var totalSize = 0;
        group.episodes.forEach(function (d) { totalSize += d.size || 0; });
        addTitle(card, group.seriesName, group.episodes.length + ' épisode(s)' + (totalSize > 0 ? ' · ' + formatSize(totalSize) : ''));

        var fired = longPress(card, function () {
            if (!selectionMode) enterSelection();
            toggleSelect(allIds);
        });

        card.addEventListener('click', function () {
            if (fired.v) { fired.v = false; return; }
            if (selectionMode) { toggleSelect(allIds); return; }
            view = { mode: 'series', seriesKey: group.key };
            render();
        });

        return card;
    }

    // --- Rendering ---------------------------------------------------------

    function render() {
        var grid = document.getElementById(GRID_ID);
        if (!grid) return;

        var items = fetchDownloads();

        // Drop selections that no longer exist
        var existing = {};
        items.forEach(function (d) { existing[d.id] = true; });
        Object.keys(selected).forEach(function (id) { if (!existing[id]) delete selected[id]; });

        renderBar(items);

        var grouped = groupItems(items);

        grid.innerHTML = '';

        if (!items.length) {
            var empty = document.createElement('div');
            empty.style.cssText = 'grid-column:1/-1;text-align:center;color:#aaa;padding:48px 16px;';
            empty.textContent = t('NoDownloadsMessage', 'Aucun téléchargement');
            grid.appendChild(empty);
            if (selectionMode) exitSelection();
            return;
        }

        if (view.mode === 'series') {
            var g = grouped.groups[view.seriesKey];
            if (!g) { view = { mode: 'home', seriesKey: null }; render(); return; }

            // Group episodes into sections by season. Jellyfin convention: season 0 = specials/OAV.
            var seasons = {}; // seasonNumber -> [episodes]
            g.episodes.forEach(function (d) {
                var s = (typeof d.season === 'number' && d.season >= 0) ? d.season : -1;
                if (!seasons[s]) seasons[s] = [];
                seasons[s].push(d);
            });

            // Order: regular seasons ascending, then specials (0), then unknown (-1)
            var order = Object.keys(seasons).map(Number).sort(function (a, b) { return a - b; });
            order = order.filter(function (s) { return s > 0; })
                .concat(order.filter(function (s) { return s === 0; }))
                .concat(order.filter(function (s) { return s < 0; }));

            var multipleSections = order.length > 1;

            order.forEach(function (s) {
                var eps = seasons[s];
                eps.sort(function (a, b) { return a.episode - b.episode; });

                if (multipleSections || s === 0) {
                    var headerText;
                    if (s > 0) headerText = t('Season', 'Saison') + ' ' + s;
                    else if (s === 0) headerText = t('Specials', 'Spéciaux / OAV');
                    else headerText = t('Other', 'Autres');

                    var secSize = 0;
                    eps.forEach(function (d) { secSize += d.size || 0; });

                    var header = document.createElement('div');
                    header.style.cssText = 'grid-column:1/-1;display:flex;align-items:baseline;gap:8px;' +
                        'margin-top:8px;border-bottom:1px solid rgba(255,255,255,0.12);padding-bottom:6px;';
                    var hTitle = document.createElement('span');
                    hTitle.textContent = headerText;
                    hTitle.style.cssText = 'color:#fff;font-size:15px;font-weight:600;';
                    var hInfo = document.createElement('span');
                    hInfo.textContent = eps.length + ' ép.' + (secSize > 0 ? ' · ' + formatSize(secSize) : '');
                    hInfo.style.cssText = 'color:#aaa;font-size:12px;';
                    header.appendChild(hTitle);
                    header.appendChild(hInfo);
                    grid.appendChild(header);
                }

                eps.forEach(function (d) {
                    var label = d.episode >= 0 ? ('E' + d.episode) : '';
                    var sub = label + (d.size > 0 ? (label ? ' · ' : '') + formatSize(d.size) : '');
                    grid.appendChild(makeItemCard(d, sub));
                });
            });
            return;
        }

        // Home view: series folders first (alphabetical), then singles
        var keys = Object.keys(grouped.groups).sort(function (a, b) {
            return grouped.groups[a].seriesName.localeCompare(grouped.groups[b].seriesName);
        });
        keys.forEach(function (k) { grid.appendChild(makeSeriesCard(grouped.groups[k])); });
        grouped.singles.forEach(function (d) { grid.appendChild(makeItemCard(d)); });
    }

    // --- Selection ---------------------------------------------------------

    function enterSelection() { selectionMode = true; }
    function exitSelection() { selectionMode = false; selected = {}; render(); }
    function toggleSelect(ids) {
        var allSel = ids.every(function (id) { return !!selected[id]; });
        ids.forEach(function (id) {
            if (allSel) delete selected[id]; else selected[id] = true;
        });
        render();
    }

    function deleteSelected() {
        var ids = Object.keys(selected);
        if (!ids.length) return;
        var msg = t('ConfirmDeletion', 'Supprimer les téléchargements sélectionnés ?');
        if (!window.confirm(msg)) return;
        try {
            window.NativeInterface.deleteDownloads(JSON.stringify(ids.map(Number)));
        } catch (e) { /* ignore */ }
        exitSelection();
        setTimeout(render, 300);
    }

    // --- Show / hide -------------------------------------------------------

    function buildRoot() {
        var root = document.createElement('div');
        root.id = ROOT_ID;
        root.style.cssText = 'padding:16px;box-sizing:border-box;';

        var bar = document.createElement('div');
        bar.id = BAR_ID;
        bar.style.cssText = 'display:flex;align-items:center;gap:12px;margin-bottom:8px;';

        var sub = document.createElement('div');
        sub.id = SUB_ID;
        sub.style.cssText = 'margin-bottom:16px;';

        var grid = document.createElement('div');
        grid.id = GRID_ID;
        grid.style.cssText = 'display:grid;grid-template-columns:repeat(auto-fill,minmax(130px,1fr));gap:16px;';

        root.appendChild(bar);
        root.appendChild(sub);
        root.appendChild(grid);
        return root;
    }

    function show() {
        if (isActive()) return;
        var page = pageContainer();

        hiddenNodes = [];
        for (var i = 0; i < page.children.length; i++) {
            var child = page.children[i];
            if (child.id === ROOT_ID) continue;
            hiddenNodes.push(child);
        }
        hiddenNodes.forEach(function (n) {
            n.setAttribute('data-nd-display', n.style.display || '');
            n.style.display = 'none';
        });

        view = { mode: 'home', seriesKey: null };
        rootEl = buildRoot();
        page.appendChild(rootEl);
        render();
        startPoll();
    }

    function hide() {
        stopPoll();
        selectionMode = false;
        selected = {};
        view = { mode: 'home', seriesKey: null };
        if (rootEl && rootEl.parentNode) rootEl.parentNode.removeChild(rootEl);
        rootEl = null;
        hiddenNodes.forEach(function (n) {
            n.style.display = n.getAttribute('data-nd-display') || '';
            n.removeAttribute('data-nd-display');
        });
        hiddenNodes = [];
    }

    function startPoll() {
        stopPoll();
        pollTimer = setInterval(function () { if (isActive()) render(); }, 1000);
    }
    function stopPoll() {
        if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
    }

    // --- Tab injection -----------------------------------------------------

    function openTab(event) {
        event.preventDefault();
        event.stopPropagation();
        show();
    }

    function injectInto(slider) {
        if (!slider || slider.querySelector('#' + TAB_ID)) return;
        var sample = slider.querySelector('.emby-tab-button');
        if (!sample) return;

        var button = document.createElement('button');
        button.id = TAB_ID;
        button.type = 'button';
        button.setAttribute('is', 'emby-button');
        button.className = sample.className
            .replace(/emby-tab-button-active/g, '')
            .replace(/\s+/g, ' ')
            .trim();
        button.textContent = t('Downloads', 'Téléchargements');
        button.addEventListener('click', openTab, true);
        slider.appendChild(button);
    }

    function scan() {
        var sliders = document.querySelectorAll('.headerTabs .emby-tabs-slider, .sectionTabs .emby-tabs-slider');
        for (var i = 0; i < sliders.length; i++) injectInto(sliders[i]);
    }

    // Leave our page when switching to a real tab or navigating elsewhere.
    document.addEventListener('click', function (event) {
        if (!isActive() || !event.target.closest) return;
        if (rootEl && rootEl.contains(event.target)) return;
        var tab = event.target.closest('.emby-tab-button');
        if (tab && tab.id !== TAB_ID) { hide(); return; }
        var link = event.target.closest('a');
        if (link) hide();
    }, true);
    window.addEventListener('hashchange', function () { if (isActive()) hide(); });
    window.addEventListener('popstate', function () { if (isActive()) hide(); });

    function start() {
        var observer = new MutationObserver(function () { scan(); });
        observer.observe(document.body, { childList: true, subtree: true });
        scan();
    }

    if (document.body) start();
    else document.addEventListener('DOMContentLoaded', start);
})();
