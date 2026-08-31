/* 2Libra native bridge. This script emits typed requests and keeps only a
 * page-scoped compatibility callback; it never reads cookies, files, or
 * uploads bytes. After a native upload completes, it only writes the returned
 * Markdown into the editor that initiated the request. Native code still
 * treats every field as untrusted and validates origin/frame/route again. */
(function () {
  'use strict';

  var BRIDGE_NAME = 'libraNative';
  var SITE_ORIGIN = 'https://2libra.com';
  var MEDIA_ORIGIN = 'https://r2.2libra.com';
  var MAX_URL_LENGTH = 4096;
  var MAX_PREVIEW_ITEMS = 50;
  var MOBILE_LAYOUT_QUERY = '(max-width: 640px)';
  var POST_NAVBAR_STYLE_ID = 'libra-post-navbar-style';
  var POST_PAGE_CLASS = 'libra-post-page';
  var POST_LIST_PAGE_CLASS = 'libra-post-list-page';
  var POST_LIST_HEADER_CLASS = 'libra-post-list-header-hidden';
  var INLINE_PROFILE_TABS = ['about', 'post', 'comment', 'favorites', 'history'];
  var UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
  var uploadEventListeners = [];
  var uploadEditorTargets = Object.create(null);
  var UPLOAD_TARGET_TTL_MILLIS = 5 * 60 * 1000;
  var lastUserAvatarUrl = null;
  var lastUserName = null;
  var PAGINATION_LIST_ITEM_SELECTOR = 'ul.card > li.items-center';
  var PAGINATION_CARD_SELECTOR = '[data-main-left] div.card[class~="border-base-content/10"]';
  var PAGINATION_ACTIVE_SELECTOR = '.join-item.btn.btn-sm.btn-active';
  var PAGINATION_PAGE_PATTERN = /^[1-9][0-9]*$/;
  var PAGINATION_CHECK_DELAY_MILLIS = 100;
  var PAGINATION_RECHECK_DELAY_MILLIS = 600;
  var paginationLoading = false;
  var paginationCheckTimer = null;

  function uuid() {
    if (window.crypto && typeof window.crypto.randomUUID === 'function') {
      return window.crypto.randomUUID();
    }
    if (!window.crypto || typeof window.crypto.getRandomValues !== 'function') {
      return null;
    }
    var bytes = new Uint8Array(16);
    window.crypto.getRandomValues(bytes);
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    var hex = Array.prototype.map.call(bytes, function (b) {
      return ('0' + b.toString(16)).slice(-2);
    }).join('');
    return hex.slice(0, 8) + '-' + hex.slice(8, 12) + '-' +
      hex.slice(12, 16) + '-' + hex.slice(16, 20) + '-' + hex.slice(20);
  }

  function bridge() {
    var value = window[BRIDGE_NAME];
    return value && typeof value.postMessage === 'function' ? value : null;
  }

  function emit(action, payload, requestIdOverride) {
    var target = bridge();
    var requestId = requestIdOverride || uuid();
    if (!target || !requestId || !payload) return false;
    if (!UUID_PATTERN.test(requestId)) return false;
    var message = {
      version: 1,
      requestId: requestId,
      action: action,
      payload: payload
    };
    try {
      target.postMessage(JSON.stringify(message));
      return true;
    } catch (_) {
      return false;
    }
  }

  function editorTextInput(editor) {
    if (!editor || !editor.querySelector) return null;
    return editor.querySelector('textarea.w-md-editor-text-input, textarea');
  }

  function dispatchEditorEvent(input, type) {
    var event;
    if (typeof Event === 'function') {
      event = new Event(type, { bubbles: true });
    } else {
      event = document.createEvent('Event');
      event.initEvent(type, true, false);
    }
    input.dispatchEvent(event);
  }

  function setEditorValue(input, value, notifyChange) {
    var prototype = window.HTMLTextAreaElement && window.HTMLTextAreaElement.prototype;
    var descriptor = prototype && Object.getOwnPropertyDescriptor(prototype, 'value');
    if (descriptor && descriptor.set) {
      descriptor.set.call(input, value);
    } else {
      input.value = value;
    }
    dispatchEditorEvent(input, 'input');
    if (notifyChange !== false) dispatchEditorEvent(input, 'change');
  }

  function insertEditorText(editor, text) {
    var input = editorTextInput(editor);
    if (!input) return false;
    var value = input.value || '';
    var start = typeof input.selectionStart === 'number' ? input.selectionStart : value.length;
    var end = typeof input.selectionEnd === 'number' ? input.selectionEnd : start;
    var next = value.slice(0, start) + text + value.slice(end);
    setEditorValue(input, next);
    var cursor = start + text.length;
    if (typeof input.setSelectionRange === 'function') input.setSelectionRange(cursor, cursor);
    return true;
  }

  function replaceEditorText(editor, search, replacement) {
    var input = editorTextInput(editor);
    if (!input) return false;
    var value = input.value || '';
    var index = value.indexOf(search);
    if (index < 0) return false;
    var next = value.slice(0, index) + replacement + value.slice(index + search.length);
    setEditorValue(input, next);
    var cursor = index + replacement.length;
    if (typeof input.setSelectionRange === 'function') input.setSelectionRange(cursor, cursor);
    return true;
  }

  function isAttached(node) {
    return !!node && !!document.documentElement && document.documentElement.contains(node);
  }

  function editorForUploadTarget(target, marker) {
    if (marker) {
      var inputs = document.querySelectorAll('textarea.w-md-editor-text-input, textarea');
      for (var i = 0; i < inputs.length; i++) {
        if ((inputs[i].value || '').indexOf(marker) < 0) continue;
        var markedEditor = inputs[i].closest('.w-md-editor');
        if (markedEditor) return markedEditor;
      }
    }

    if (isAttached(target.editor)) return target.editor;

    var editors = document.querySelectorAll('.w-md-editor');
    if (typeof target.editorIndex === 'number' &&
        target.editorIndex >= 0 && target.editorIndex < editors.length) {
      return editors[target.editorIndex];
    }
    return target.editor;
  }

  function setEditorSelection(input, start) {
    if (typeof input.setSelectionRange === 'function') {
      input.setSelectionRange(start, start);
    }
  }

  /* React-controlled textareas can render once after the native setter and
   * restore the previous state.  Retry only while the value is still the
   * upload value (or empty/marker-only), so a user's subsequent edit wins. */
  function scheduleCompletedEditorRepair(
    target,
    placeholder,
    beforeValue,
    expectedValue,
    markdown,
    expectedCursor
  ) {
    var delays = [0, 50, 160, 400, 1000];
    var attempt = 0;
    var repair = function () {
      var editor = editorForUploadTarget(target, placeholder);
      var input = editorTextInput(editor);
      if (!input) {
        if (attempt < delays.length) {
          window.setTimeout(repair, delays[attempt++]);
        }
        return;
      }

      var current = input.value || '';
      if (current === expectedValue ||
          (current.indexOf(markdown) >= 0 &&
           (!placeholder || current.indexOf(placeholder) < 0))) return;

      var canRepair = current === '' || current === beforeValue ||
        (placeholder && current.indexOf(placeholder) >= 0);
      if (!canRepair) return;

      var next = expectedValue;
      if (placeholder && current.indexOf(placeholder) >= 0) {
        next = current.replace(placeholder, markdown);
      }
      setEditorValue(input, next, false);
      setEditorSelection(input, Math.min(expectedCursor, next.length));
      if (attempt < delays.length) {
        window.setTimeout(repair, delays[attempt++]);
      }
    };
    window.setTimeout(repair, delays[attempt++]);
  }

  function scheduleCompletedEditorWrite(target, placeholder, markdown) {
    var delays = [0, 50, 160, 400, 1000];
    var attempt = 0;
    var retry = function () {
      if (writeCompletedMarkdown(target, placeholder, markdown)) return;
      if (attempt < delays.length) window.setTimeout(retry, delays[attempt++]);
    };
    window.setTimeout(retry, delays[attempt++]);
  }

  function writeCompletedMarkdown(target, placeholder, markdown) {
    var editor = editorForUploadTarget(target, placeholder);
    var input = editorTextInput(editor);
    if (!input) return false;

    var beforeValue = input.value || '';
    var index = placeholder ? beforeValue.indexOf(placeholder) : -1;
    var next;
    var cursor;
    if (index >= 0) {
      next = beforeValue.slice(0, index) + markdown +
        beforeValue.slice(index + placeholder.length);
      cursor = index + markdown.length;
    } else {
      var start = typeof input.selectionStart === 'number' ? input.selectionStart : beforeValue.length;
      var end = typeof input.selectionEnd === 'number' ? input.selectionEnd : start;
      next = beforeValue.slice(0, start) + markdown + beforeValue.slice(end);
      cursor = start + markdown.length;
    }

    setEditorValue(input, next);
    setEditorSelection(input, cursor);
    scheduleCompletedEditorRepair(
      target,
      placeholder,
      beforeValue,
      next,
      markdown,
      cursor
    );
    return true;
  }

  function rememberUploadTarget(requestId, editor) {
    if (!editor) return;
    var editors = document.querySelectorAll('.w-md-editor');
    var editorIndex = -1;
    for (var i = 0; i < editors.length; i++) {
      if (editors[i] === editor) {
        editorIndex = i;
        break;
      }
    }
    uploadEditorTargets[requestId] = {
      editor: editor,
      editorIndex: editorIndex,
      placeholders: Object.create(null),
      cleanupTimer: null
    };
  }

  function forgetUploadTarget(requestId) {
    var target = uploadEditorTargets[requestId];
    if (!target) return;
    if (target.cleanupTimer) window.clearTimeout(target.cleanupTimer);
    delete uploadEditorTargets[requestId];
  }

  function scheduleUploadTargetCleanup(requestId) {
    var target = uploadEditorTargets[requestId];
    if (!target || target.cleanupTimer) return;
    target.cleanupTimer = window.setTimeout(function () {
      delete uploadEditorTargets[requestId];
    }, UPLOAD_TARGET_TTL_MILLIS);
  }

  function clearUploadTargetTimer(target) {
    if (!target || !target.cleanupTimer) return;
    window.clearTimeout(target.cleanupTimer);
    target.cleanupTimer = null;
  }

  function isUuid(value) {
    return typeof value === 'string' && UUID_PATTERN.test(value);
  }

  function handleUploadEvent(parsed) {
    if (!isUuid(parsed.requestId)) return;
    var target = uploadEditorTargets[parsed.requestId];
    if (!target) return;
    clearUploadTargetTimer(target);

    var payload = parsed.payload || {};
    var clientId = payload.clientId;
    if (parsed.event === 'image_upload_selected') {
      if (!isUuid(clientId) || target.placeholders[clientId]) return;
      var marker = '<!-- 2libra-upload:' + clientId + ' -->';
      var selectedEditor = editorForUploadTarget(target);
      if (insertEditorText(selectedEditor, marker)) target.placeholders[clientId] = marker;
      return;
    }

    if (parsed.event === 'image_upload_completed') {
      if (!isUuid(clientId) || typeof payload.markdown !== 'string' || !payload.markdown) return;
      var placeholder = target.placeholders[clientId];
      if (!writeCompletedMarkdown(target, placeholder, payload.markdown)) {
        scheduleCompletedEditorWrite(target, placeholder, payload.markdown);
      }
      delete target.placeholders[clientId];
      return;
    }

    if (parsed.event === 'image_upload_failed' || parsed.event === 'image_upload_cancelled') {
      if (isUuid(clientId)) {
        var failedPlaceholder = target.placeholders[clientId];
        if (failedPlaceholder) {
          replaceEditorText(
            editorForUploadTarget(target, failedPlaceholder),
            failedPlaceholder,
            ''
          );
        }
        delete target.placeholders[clientId];
      }
      return;
    }

    if (parsed.event === 'image_upload_batch_cancelled') {
      Object.keys(target.placeholders).forEach(function (id) {
        replaceEditorText(
          editorForUploadTarget(target, target.placeholders[id]),
          target.placeholders[id],
          ''
        );
      });
      forgetUploadTarget(parsed.requestId);
      return;
    }

    if (parsed.event === 'image_upload_batch_finished') scheduleUploadTargetCleanup(parsed.requestId);
  }

  /* The reply proxy posts upload events back to the injected object. Expose a
   * small value-only listener API so the page can observe upload state. The
   * bridge itself handles the successful Markdown replacement in the editor
   * that initiated the request. */
  function installReplyListener() {
    var target = bridge();
    if (!target || typeof target.addEventListener !== 'function') return;
    target.addEventListener('message', function (event) {
      var value = event && event.data;
      if (typeof value !== 'string') return;
      var parsed;
      try { parsed = JSON.parse(value); } catch (_) { return; }
      if (!parsed || typeof parsed.event !== 'string') return;
      uploadEventListeners.slice().forEach(function (listener) {
        try { listener(parsed); } catch (_) {}
      });
      if (typeof window.CustomEvent === 'function') {
        window.dispatchEvent(new CustomEvent('libra-upload-event', { detail: parsed }));
      }
      /* Let page listeners finish first. The native bridge owns the final
       * editor write, so a page-side upload listener cannot clear it after a
       * successful upload event. */
      handleUploadEvent(parsed);
    });
  }

  function absolute(value, base) {
    if (value == null || value === '') return null;
    try {
      var url = new URL(value, base || document.location.href);
      return url.href.length <= MAX_URL_LENGTH ? url : null;
    } catch (_) {
      return null;
    }
  }

  function normalizedPath(pathname) {
    return pathname.replace(/\/+$/, '') || '/';
  }

  function isPostListPath(pathname) {
    var path = normalizedPath(pathname || '/');
    return path === '/' ||
      path === '/post/hot/today' ||
      path === '/post/hot/recent' ||
      path === '/post/latest' ||
      path.indexOf('/node/') === 0;
  }

  function paginationPage(url) {
    if (!url || url.origin !== SITE_ORIGIN || !isPostListPath(url.pathname)) return null;
    if (!url.search) return '1';
    var query = url.search.slice(1);
    if (!query || query.indexOf('&') >= 0) return null;
    var separator = query.indexOf('=');
    if (separator <= 0 || separator !== query.lastIndexOf('=')) return null;
    if (query.slice(0, separator) !== 'p') return null;
    var page = query.slice(separator + 1);
    return PAGINATION_PAGE_PATTERN.test(page) ? page : null;
  }

  function isPaginationLink(baseUrl, targetUrl) {
    return !!(
      baseUrl && targetUrl &&
      baseUrl.origin === SITE_ORIGIN &&
      targetUrl.origin === SITE_ORIGIN &&
      normalizedPath(baseUrl.pathname) === normalizedPath(targetUrl.pathname) &&
      paginationPage(baseUrl) !== null &&
      paginationPage(targetUrl) !== null
    );
  }

  function isSamePaginationPage(firstUrl, secondUrl) {
    return isPaginationLink(firstUrl, secondUrl) &&
      paginationPage(firstUrl) === paginationPage(secondUrl);
  }

  function isPaginationUrl(url) {
    return isPaginationLink(absolute(document.location.href), url);
  }

  function isPostUrl(url) {
    if (!url || url.origin !== SITE_ORIGIN || isPostListPath(url.pathname)) return false;
    var parts = url.pathname.split('/').filter(Boolean);
    return parts.length === 3 && parts[0] === 'post';
  }

  function isSafeUsername(username) {
    return typeof username === 'string' && username.length >= 1 && username.length <= 64 &&
      username !== '.' && username !== '..' && /^[-A-Za-z0-9._~]+$/.test(username);
  }

  function profileTab(url) {
    if (!url || url.origin !== SITE_ORIGIN) return null;
    var parts = url.pathname.replace(/^\/+|\/+$/g, '').split('/');
    if (parts.length !== 3 || parts[0] !== 'user' || !isSafeUsername(parts[1]) ||
        INLINE_PROFILE_TABS.indexOf(parts[2]) < 0) return null;
    return { username: parts[1], tab: parts[2] };
  }

  function isInlineProfileTabUrl(url) {
    var current = profileTab(absolute(document.location.href));
    var target = profileTab(url);
    return !!(current && target && current.username === target.username);
  }

  function isPostPage() {
    if (document.location.origin !== SITE_ORIGIN) return false;
    var path = document.location.pathname.replace(/\/+$/, '') || '/';
    return path === '/' || path === '/post' || path.indexOf('/post/') === 0;
  }

  function isPostListPage() {
    if (document.location.origin !== SITE_ORIGIN) return false;
    var path = normalizedPath(document.location.pathname);
    return path === '/post/hot/today' ||
      path === '/post/hot/recent' ||
      path === '/post/latest';
  }

  function isPaginatedPostListPage() {
    return document.location.origin === SITE_ORIGIN &&
      isPostListPath(document.location.pathname);
  }

  function postIdentity(item, baseUrl) {
    if (!item || !item.querySelectorAll) return null;
    var anchors = item.querySelectorAll('a[href]');
    var base = baseUrl && baseUrl.href ? baseUrl.href : document.location.href;
    for (var i = 0; i < anchors.length; i++) {
      var url = absolute(anchors[i].getAttribute('href'), base);
      if (isPostUrl(url)) return url.origin + normalizedPath(url.pathname);
    }
    return null;
  }

  function chooseLargestListGroup(groups) {
    var selected = null;
    for (var i = 0; i < groups.length; i++) {
      if (!selected || groups[i].items.length > selected.items.length) selected = groups[i];
    }
    return selected;
  }

  function findListStructure(root, baseUrl) {
    var primaryNodes = root.querySelectorAll(PAGINATION_LIST_ITEM_SELECTOR);
    var primaryGroups = [];
    for (var i = 0; i < primaryNodes.length; i++) {
      if (!postIdentity(primaryNodes[i], baseUrl)) continue;
      var primaryContainer = primaryNodes[i].parentElement;
      if (!primaryContainer) continue;
      var primaryGroup = null;
      for (var j = 0; j < primaryGroups.length; j++) {
        if (primaryGroups[j].container === primaryContainer) {
          primaryGroup = primaryGroups[j];
          break;
        }
      }
      if (!primaryGroup) {
        primaryGroup = { kind: 'list', container: primaryContainer, items: [] };
        primaryGroups.push(primaryGroup);
      }
      primaryGroup.items.push(primaryNodes[i]);
    }
    var selectedPrimary = chooseLargestListGroup(primaryGroups);
    if (selectedPrimary) return selectedPrimary;

    var cardNodes = root.querySelectorAll(PAGINATION_CARD_SELECTOR);
    var cardGroups = [];
    for (var k = 0; k < cardNodes.length; k++) {
      if (!postIdentity(cardNodes[k], baseUrl)) continue;
      var cardContainer = cardNodes[k].parentElement;
      if (!cardContainer) continue;
      var cardGroup = null;
      for (var m = 0; m < cardGroups.length; m++) {
        if (cardGroups[m].container === cardContainer) {
          cardGroup = cardGroups[m];
          break;
        }
      }
      if (!cardGroup) {
        cardGroup = { kind: 'cards', container: cardContainer, items: [] };
        cardGroups.push(cardGroup);
      }
      cardGroup.items.push(cardNodes[k]);
    }
    return chooseLargestListGroup(cardGroups);
  }

  function paginationGroup(active) {
    if (!active) return null;
    if (active.closest) {
      var join = active.closest('.join');
      if (join) return join;
    }
    var node = active.parentElement;
    while (node && node !== document.body) {
      if (node.querySelectorAll(PAGINATION_ACTIVE_SELECTOR).length >= 1 &&
          node.querySelectorAll('.join-item.btn.btn-sm').length >= 2) {
        return node;
      }
      node = node.parentElement;
    }
    return active.parentElement;
  }

  function findPagination(root, baseUrl, requireNext) {
    var activeNodes = root.querySelectorAll(PAGINATION_ACTIVE_SELECTOR);
    for (var i = 0; i < activeNodes.length; i++) {
      var active = activeNodes[i];
      var group = paginationGroup(active);
      if (!group) continue;
      var links = group.querySelectorAll('a[href]');
      var hasPageLink = false;
      for (var j = 0; j < links.length; j++) {
        var linkUrl = absolute(links[j].getAttribute('href'), baseUrl && baseUrl.href);
        if (isPaginationLink(baseUrl, linkUrl)) {
          hasPageLink = true;
          break;
        }
      }
      if (!hasPageLink) continue;

      var next = active.nextElementSibling;
      var nextUrl = next && next.tagName === 'A'
        ? absolute(next.getAttribute('href'), baseUrl && baseUrl.href)
        : null;
      if (requireNext && !isPaginationLink(baseUrl, nextUrl)) continue;
      return { active: active, group: group, nextUrl: isPaginationLink(baseUrl, nextUrl) ? nextUrl : null };
    }
    return null;
  }

  function directChildUnder(container, node) {
    var current = node;
    while (current && current.parentElement && current.parentElement !== container) {
      current = current.parentElement;
    }
    return current && current.parentElement === container ? current : null;
  }

  function importedNode(node) {
    return document.importNode ? document.importNode(node, true) : node.cloneNode(true);
  }

  function appendFetchedPage(currentUrl, responseUrl, responseDocument) {
    var currentList = findListStructure(document, currentUrl);
    var fetchedList = findListStructure(responseDocument, responseUrl);
    var currentPagination = findPagination(document, currentUrl, true);
    var fetchedPagination = findPagination(responseDocument, responseUrl, false);
    if (!currentList || !fetchedList || !currentPagination || !fetchedPagination) return false;
    if (currentList.kind !== fetchedList.kind) return false;

    var existing = Object.create(null);
    for (var i = 0; i < currentList.items.length; i++) {
      var currentId = postIdentity(currentList.items[i], currentUrl);
      if (currentId) existing[currentId] = true;
    }

    var newItems = [];
    for (var j = 0; j < fetchedList.items.length; j++) {
      var fetchedId = postIdentity(fetchedList.items[j], responseUrl);
      if (!fetchedId || existing[fetchedId]) continue;
      existing[fetchedId] = true;
      newItems.push(fetchedList.items[j]);
    }
    if (!newItems.length || !currentPagination.group.parentNode) return false;

    var fragment = document.createDocumentFragment();
    for (var k = 0; k < newItems.length; k++) fragment.appendChild(importedNode(newItems[k]));
    var replacement = importedNode(fetchedPagination.group);
    var reference = directChildUnder(currentList.container, currentPagination.group);
    if (reference) {
      currentList.container.insertBefore(fragment, reference);
    } else {
      currentList.container.appendChild(fragment);
    }

    currentPagination.group.parentNode.replaceChild(replacement, currentPagination.group);
    return true;
  }

  function nearPageBottom() {
    var root = document.documentElement;
    if (!root) return false;
    var viewportHeight = window.innerHeight || root.clientHeight || 0;
    var scrollTop = window.pageYOffset || root.scrollTop || 0;
    return root.scrollHeight - scrollTop - viewportHeight <= viewportHeight;
  }

  function loadNextPaginationPage() {
    if (paginationLoading || !isPaginatedPostListPage() || !nearPageBottom()) return;
    var currentUrl = absolute(document.location.href);
    var currentPagination = findPagination(document, currentUrl, true);
    if (!currentUrl || !currentPagination || !currentPagination.nextUrl) return;
    var nextUrl = currentPagination.nextUrl;
    paginationLoading = true;

    var request;
    try {
      request = window.fetch(nextUrl.href, {
        credentials: 'same-origin',
        headers: { Accept: 'text/html' },
        redirect: 'follow'
      });
    } catch (_) {
      paginationLoading = false;
      return;
    }

    Promise.resolve(request)
      .then(function (response) {
        if (!response || !response.ok) throw new Error('pagination response failed');
        var responseUrl = absolute(response.url, nextUrl.href);
        if (!responseUrl || !isSamePaginationPage(nextUrl, responseUrl)) {
          throw new Error('pagination response route mismatch');
        }
        var contentType = response.headers && typeof response.headers.get === 'function'
          ? response.headers.get('content-type')
          : null;
        if (contentType && contentType.toLowerCase().indexOf('text/html') < 0) {
          throw new Error('pagination response is not html');
        }
        return response.text().then(function (html) {
          return { responseUrl: responseUrl, html: html };
        });
      })
      .then(function (result) {
        if (!result || typeof DOMParser !== 'function') return false;
        var responseDocument = new DOMParser().parseFromString(result.html, 'text/html');
        if (!responseDocument || !responseDocument.documentElement) return false;
        return appendFetchedPage(currentUrl, result.responseUrl, responseDocument);
      })
      .then(function (appended) {
        paginationLoading = false;
        if (appended) schedulePaginationCheck(PAGINATION_RECHECK_DELAY_MILLIS);
      })
      .catch(function () {
        paginationLoading = false;
      });
  }

  function schedulePaginationCheck(delay) {
    if (paginationCheckTimer !== null) return;
    paginationCheckTimer = window.setTimeout(function () {
      paginationCheckTimer = null;
      loadNextPaginationPage();
    }, typeof delay === 'number' ? delay : PAGINATION_CHECK_DELAY_MILLIS);
  }

  function postListHeader(navigationControl) {
    var ancestor = navigationControl;
    while (ancestor && ancestor !== document.body) {
      var classes = ancestor.classList;
      if (ancestor.tagName === 'DIV' && classes &&
          classes.contains('px-2') && classes.contains('py-1') &&
          classes.contains('border-b') &&
          classes.contains('border-base-content/10') &&
          classes.contains('flex') && classes.contains('items-center') &&
          classes.contains('justify-between') && classes.contains('block') &&
          classes.contains('lg:hidden')) {
        return ancestor;
      }
      ancestor = ancestor.parentElement;
    }
    return null;
  }

  function updatePostListHeaderVisibility() {
    var root = document.documentElement;
    if (!root) return;
    var hidden = isPostListPage();
    root.classList.toggle(POST_LIST_PAGE_CLASS, hidden);
    var navigationControls = document.querySelectorAll(
      'a[href="/post/hot/today"], a[href="/post/hot/recent"], a[href="/post/latest"]'
    );
    for (var i = 0; i < navigationControls.length; i++) {
      var header = postListHeader(navigationControls[i]);
      if (header) header.classList.toggle(POST_LIST_HEADER_CLASS, hidden);
    }
  }

  function updatePostNavbarVisibility() {
    var root = document.documentElement;
    if (!root) return;
    var style = document.getElementById(POST_NAVBAR_STYLE_ID);
    if (!style) {
      style = document.createElement('style');
      style.id = POST_NAVBAR_STYLE_ID;
      style.textContent =
        'html.' + POST_PAGE_CLASS + ' .navbar {' +
        'height: 0 !important; min-height: 0 !important; padding-block: 0 !important;' +
        'overflow: visible !important;}' +
        'html.' + POST_PAGE_CLASS + ' .navbar > .navbar-start {' +
        'display: none !important;}' +
        'html.' + POST_PAGE_CLASS + ' .navbar > .navbar-end > div.relative > ' +
        '[role="button"] > * {' +
        'visibility: hidden !important;}' +
        'html.' + POST_PAGE_CLASS + ' .navbar > .navbar-end > div.relative > ' +
        '*:not([role="button"]) {' +
        'left: auto !important; right: 0 !important;' +
        'inset-inline-start: auto !important; inset-inline-end: 0 !important;' +
        'z-index: 50 !important;}' +
        'html.' + POST_LIST_PAGE_CLASS + ' .' + POST_LIST_HEADER_CLASS + ' {' +
        'display: none !important; height: 0 !important; min-height: 0 !important;' +
        'padding: 0 !important; margin: 0 !important; border: 0 !important;' +
        'overflow: hidden !important;}';
      (document.head || root).appendChild(style);
    }
    root.classList.toggle(POST_PAGE_CLASS, isPostPage());
    updatePostListHeaderVisibility();
  }

  function alignOpenUserMenu() {
    var image = document.querySelector(
      'div.navbar-end > div.relative > [role="button"] img[src*="/avatars/"]'
    );
    var trigger = image && image.closest('[role="button"]');
    var scope = trigger && trigger.parentElement;
    if (!scope) return false;

    var candidates = scope.querySelectorAll('*');
    var menu = null;
    var largestArea = 0;
    for (var i = 0; i < candidates.length; i++) {
      var candidate = candidates[i];
      if (candidate === trigger || trigger.contains(candidate)) continue;
      var computed = window.getComputedStyle(candidate);
      if (computed.position !== 'absolute' && computed.position !== 'fixed') continue;
      var rect = candidate.getBoundingClientRect();
      var area = rect.width * rect.height;
      if (area <= largestArea) continue;
      largestArea = area;
      menu = candidate;
    }
    if (!menu) return false;

    var menuRect = menu.getBoundingClientRect();
    menu.style.setProperty('position', 'fixed', 'important');
    menu.style.setProperty('top', Math.max(0, menuRect.top) + 'px', 'important');
    menu.style.setProperty('left', 'auto', 'important');
    menu.style.setProperty('right', '0px', 'important');
    menu.style.setProperty('inset-inline-start', 'auto', 'important');
    menu.style.setProperty('inset-inline-end', '0px', 'important');
    menu.style.setProperty('transform', 'none', 'important');
    menu.style.setProperty('z-index', '1000', 'important');
    menu.style.setProperty('max-width', 'calc(100vw - 16px)', 'important');
    menu.style.setProperty('max-height', 'calc(100vh - 8px)', 'important');
    menu.style.setProperty('overflow-y', 'auto', 'important');
    return true;
  }

  function mobileUserAvatarUrl() {
    if (!window.matchMedia || !window.matchMedia(MOBILE_LAYOUT_QUERY).matches) return null;
    var image = document.querySelector(
      'div.navbar-end > div.relative > [role="button"] img[src*="/avatars/"]'
    );
    if (!image) return null;
    var url = absolute(image.currentSrc || image.src || image.getAttribute('data-src'));
    if (!url || url.protocol !== 'https:' ||
        (url.hostname !== 'r2.2libra.com' && url.hostname !== '2libra.com') ||
        url.port || url.hash || url.pathname.toLowerCase().indexOf('/avatars/') < 0) {
      return null;
    }
    return url.href;
  }

  function reportUserAvatar() {
    var url = mobileUserAvatarUrl();
    if (!url || url === lastUserAvatarUrl) return false;
    if (!emit('user_avatar', { url: url })) return false;
    lastUserAvatarUrl = url;
    return true;
  }

  function mobileUserName() {
    if (!window.matchMedia || !window.matchMedia(MOBILE_LAYOUT_QUERY).matches) return null;
    var image = document.querySelector(
      'div.navbar-end > div.relative > [role="button"] img[src*="/avatars/"]'
    );
    var trigger = image && image.closest('[role="button"]');
    if (!trigger) return null;
    var label = trigger.querySelector('div.text-xs');
    var username = label && (label.textContent || '').trim();
    if (!username && image.getAttribute) username = (image.getAttribute('alt') || '').trim();
    if (!isSafeUsername(username)) return null;
    return username;
  }

  function reportUserName() {
    var username = mobileUserName();
    if (!username || username === lastUserName) return false;
    if (!emit('user_name', { username: username })) return false;
    lastUserName = username;
    return true;
  }

  function isImageUrl(url) {
    return !!url && url.protocol === 'https:' && !!url.hostname &&
      !url.username && !url.password && (!url.port || url.port === '443') && !url.hash;
  }

  function imageUrl(image) {
    if (!image) return null;
    var candidates = [
      image.currentSrc,
      image.src,
      image.getAttribute && image.getAttribute('data-src'),
      image.getAttribute && image.getAttribute('data-original')
    ];
    for (var i = 0; i < candidates.length; i++) {
      var url = absolute(candidates[i]);
      if (isImageUrl(url)) return url;
    }
    return null;
  }

  function isNonContentImage(image) {
    if (!image || !image.closest) return false;

    /* The bridge listener runs during capture, so editor controls must be
     * left to the page before the generic image-preview handler sees them. */
    if (image.closest(
      '.w-md-editor, button, [role="button"], [role="option"], ' +
      '[data-emoji], [data-emoticon], [data-emoji-picker]'
    )) return true;

    var ancestor = image;
    while (ancestor && ancestor !== document.body) {
      var marker = [
        ancestor.id,
        typeof ancestor.className === 'string' ? ancestor.className : '',
        ancestor.getAttribute && ancestor.getAttribute('aria-label'),
        ancestor.getAttribute && ancestor.getAttribute('title')
      ].join(' ').toLowerCase();
      if (marker.indexOf('emoji') >= 0 || marker.indexOf('emoticon') >= 0) return true;
      ancestor = ancestor.parentElement;
    }
    return false;
  }

  function isPreviewableImage(image) {
    return !!imageUrl(image) && !isNonContentImage(image);
  }

  function imageElements() {
    return Array.prototype.filter.call(document.querySelectorAll('img'), function (image) {
      return isPreviewableImage(image);
    });
  }

  function onImageClick(event, image) {
    var items = imageElements();
    var urls = [];
    var clickedIndex = -1;
    items.forEach(function (item) {
      var itemUrl = imageUrl(item);
      if (!itemUrl) return;
      var itemIndex = urls.indexOf(itemUrl.href);
      if (itemIndex < 0) {
        itemIndex = urls.length;
        urls.push(itemUrl.href);
      }
      if (item === image) clickedIndex = itemIndex;
    });
    if (clickedIndex < 0 || !urls.length) return false;
    if (urls.length > MAX_PREVIEW_ITEMS) {
      var start = Math.min(
        Math.max(0, clickedIndex - Math.floor(MAX_PREVIEW_ITEMS / 2)),
        urls.length - MAX_PREVIEW_ITEMS
      );
      urls = urls.slice(start, start + MAX_PREVIEW_ITEMS);
      clickedIndex -= start;
    }
    event.preventDefault();
    event.stopPropagation();
    return emit('preview_images', { urls: urls, initialIndex: clickedIndex });
  }

  function onVideoClick(event, video) {
    var source = video.currentSrc || video.src;
    var url = absolute(source);
    if (!url || url.origin !== MEDIA_ORIGIN || !/^\/video\//.test(url.pathname)) return false;
    event.preventDefault();
    event.stopPropagation();
    var payload = {
      url: url.href,
      mimeType: video.getAttribute('type') || video.getAttribute('data-mime-type') || 'video/mp4'
    };
    if (video.getAttribute('title')) payload.title = video.getAttribute('title');
    if (video.poster) {
      var poster = absolute(video.poster);
      if (poster) payload.posterUrl = poster.href;
    }
    var vtt = video.getAttribute('data-preview-vtt-url');
    if (vtt) {
      var vttUrl = absolute(vtt);
      if (vttUrl) payload.previewVttUrl = vttUrl.href;
    }
    return emit('play_video', payload);
  }

  function onLinkClick(event, anchor) {
    var url = absolute(anchor.href);
    if (!url || event.defaultPrevented) return false;
    if (url.origin === SITE_ORIGIN) {
      if (isPaginationUrl(url)) return false;
      if (isInlineProfileTabUrl(url)) return false;
      var handled = emit(isPostUrl(url) ? 'open_post' : 'open_page', { url: url.href });
      if (!handled) return false;
      event.preventDefault();
      event.stopPropagation();
      return true;
    }
    if (url.protocol === 'https:') {
      var externalHandled = emit('open_external', { url: url.href });
      if (!externalHandled) return false;
      event.preventDefault();
      event.stopPropagation();
      return true;
    }
    return false;
  }

  function hasUserActivation() {
    return !!(window.navigator.userActivation && window.navigator.userActivation.isActive);
  }

  function wire(root) {
    var scope = root || document;
    scope.addEventListener('click', function (event) {
      var target = event.target;
      if (!target || !target.closest) return;
      var image = target.closest('img');
      if (image && isPreviewableImage(image)) {
        onImageClick(event, image);
        return;
      }
      var video = target.closest('video');
      if (video && onVideoClick(event, video)) return;
      var pick = target.closest('[data-libra-pick-images]');
      if (pick) {
        event.preventDefault();
        event.stopPropagation();
        var editor = pick.closest('.w-md-editor');
        var requestId = uuid();
        if (requestId) rememberUploadTarget(requestId, editor);
        var handled = !!(requestId && emit(
          'pick_and_upload_images',
          {},
          requestId
        ));
        if (!handled && requestId) forgetUploadTarget(requestId);
        return;
      }
      var share = target.closest('[data-libra-share-post]');
      if (share) {
        event.preventDefault();
        event.stopPropagation();
        var shareUrl = absolute(share.getAttribute('data-libra-share-post'));
        if (shareUrl && isPostUrl(shareUrl)) {
          emit('share_post', { url: shareUrl.href, title: document.title.slice(0, 160) });
        }
        return;
      }
      var anchor = target.closest('a');
      if (anchor) onLinkClick(event, anchor);
    }, true);
  }

  /* Next.js also changes routes through history.pushState/replaceState.  Only
   * a user-activated route change is a native navigation; initialization and
   * other automatic history updates must remain in the source document. Login
   * or a WebView implementation without the bridge keeps the normal browser
   * behavior by falling through to the original method. */
  function interceptHistory(method) {
    var original = window.history[method];
    if (typeof original !== 'function') return;
    window.history[method] = function (state, title, url) {
      if (url == null) return original.apply(window.history, arguments);
      var next = absolute(url);
      if (next && next.origin === SITE_ORIGIN && hasUserActivation() &&
          !isInlineProfileTabUrl(next) && !isPaginationUrl(next)) {
        var handled = emit(isPostUrl(next) ? 'open_post' : 'open_page', { url: next.href });
        if (handled) return undefined;
      }
      var result = original.apply(window.history, arguments);
      updatePostNavbarVisibility();
      schedulePaginationCheck(PAGINATION_CHECK_DELAY_MILLIS);
      return result;
    };
  }

  interceptHistory('pushState');
  interceptHistory('replaceState');
  window.addEventListener('popstate', updatePostNavbarVisibility);
  window.addEventListener('scroll', function () {
    schedulePaginationCheck(PAGINATION_CHECK_DELAY_MILLIS);
  }, false);

  /* Optional toolbar affordance: add one button to every editor instance.
   * Reply editors are mounted after the initial page load, so this function
   * is intentionally idempotent and is called by the MutationObserver below. */
  function injectPicker() {
    var toolbars = document.querySelectorAll(
      '.w-md-editor > div.w-md-editor-toolbar > ul:nth-child(1)'
    );
    Array.prototype.forEach.call(toolbars, function (toolbar) {
      if (toolbar.querySelector('[data-libra-native-picker]')) return;
      var item = document.createElement('li');
      var button = document.createElement('button');
      item.setAttribute('data-libra-native-picker-item', 'true');
      button.type = 'button';
      button.setAttribute('data-libra-native-picker', 'true');
      button.setAttribute('data-libra-pick-images', 'true');
      button.setAttribute('data-name', 'libra-native-picker');
      button.setAttribute('aria-label', '插入图片');
      button.setAttribute('title', '插入图片');
      button.textContent = '图片';
      item.appendChild(button);
      toolbar.appendChild(item);
    });
  }

  var publicApi = window.LibraNativeBridge || {};
  publicApi.retryImageUpload = function (requestId, clientId) {
    if (typeof requestId !== 'string' || !UUID_PATTERN.test(requestId) ||
        typeof clientId !== 'string' || !UUID_PATTERN.test(clientId)) return false;
    return emit('retry_image_upload', { clientId: clientId }, requestId);
  };
  publicApi.onUploadEvent = function (listener) {
    if (typeof listener !== 'function') return function () {};
    uploadEventListeners.push(listener);
    return function () {
      uploadEventListeners = uploadEventListeners.filter(function (item) { return item !== listener; });
    };
  };
  publicApi.alignUserMenu = alignOpenUserMenu;
  window.LibraNativeBridge = publicApi;

  updatePostNavbarVisibility();
  wire(document);
  installReplyListener();
  injectPicker();
  reportUserAvatar();
  reportUserName();
  if (window.MutationObserver) {
    new MutationObserver(function () {
      updatePostNavbarVisibility();
      injectPicker();
      reportUserAvatar();
      reportUserName();
      alignOpenUserMenu();
      schedulePaginationCheck(PAGINATION_CHECK_DELAY_MILLIS);
    }).observe(document.documentElement, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ['class', 'aria-expanded']
    });
  }
  window.addEventListener('resize', function () {
    reportUserAvatar();
    reportUserName();
    schedulePaginationCheck(PAGINATION_CHECK_DELAY_MILLIS);
  });
  schedulePaginationCheck(PAGINATION_CHECK_DELAY_MILLIS);
})();
