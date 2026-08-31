/* 2Libra native bridge. This script emits typed requests or delegates to a
 * page-owned compatibility input; it never reads cookies, files, or uploads
 * bytes. After a native upload completes, it only writes the returned Markdown
 * into the editor that initiated the request. Native code still treats every
 * field as untrusted and validates origin/frame/route again. */
(function () {
  'use strict';

  var BRIDGE_NAME = 'libraNative';
  var SITE_ORIGIN = 'https://2libra.com';
  var MEDIA_ORIGIN = 'https://r2.2libra.com';
  var MAX_URL_LENGTH = 4096;
  var MAX_PREVIEW_ITEMS = 50;
  var UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
  var uploadEventListeners = [];
  var uploadEditorTargets = Object.create(null);
  var UPLOAD_TARGET_TTL_MILLIS = 5 * 60 * 1000;

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

  function setEditorValue(input, value) {
    var prototype = window.HTMLTextAreaElement && window.HTMLTextAreaElement.prototype;
    var descriptor = prototype && Object.getOwnPropertyDescriptor(prototype, 'value');
    if (descriptor && descriptor.set) {
      descriptor.set.call(input, value);
    } else {
      input.value = value;
    }
    dispatchEditorEvent(input, 'input');
    dispatchEditorEvent(input, 'change');
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

  function rememberUploadTarget(requestId, editor) {
    if (!editor) return;
    uploadEditorTargets[requestId] = {
      editor: editor,
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
      if (insertEditorText(target.editor, marker)) target.placeholders[clientId] = marker;
      return;
    }

    if (parsed.event === 'image_upload_completed') {
      if (!isUuid(clientId) || typeof payload.markdown !== 'string' || !payload.markdown) return;
      var placeholder = target.placeholders[clientId];
      if (!placeholder || !replaceEditorText(target.editor, placeholder, payload.markdown)) {
        insertEditorText(target.editor, payload.markdown);
      }
      delete target.placeholders[clientId];
      return;
    }

    if (parsed.event === 'image_upload_failed' || parsed.event === 'image_upload_cancelled') {
      if (isUuid(clientId)) {
        var failedPlaceholder = target.placeholders[clientId];
        if (failedPlaceholder) replaceEditorText(target.editor, failedPlaceholder, '');
        delete target.placeholders[clientId];
      }
      return;
    }

    if (parsed.event === 'image_upload_batch_cancelled') {
      Object.keys(target.placeholders).forEach(function (id) {
        replaceEditorText(target.editor, target.placeholders[id], '');
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
      handleUploadEvent(parsed);
      uploadEventListeners.slice().forEach(function (listener) {
        try { listener(parsed); } catch (_) {}
      });
      if (typeof window.CustomEvent === 'function') {
        window.dispatchEvent(new CustomEvent('libra-upload-event', { detail: parsed }));
      }
    });
  }

  function absolute(value) {
    if (value == null || value === '') return null;
    try {
      var url = new URL(value, document.location.href);
      return url.href.length <= MAX_URL_LENGTH ? url : null;
    } catch (_) {
      return null;
    }
  }

  function isPostUrl(url) {
    if (!url || url.origin !== SITE_ORIGIN) return false;
    var parts = url.pathname.split('/').filter(Boolean);
    return parts.length === 3 && parts[0] === 'post';
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

  function imageElements() {
    return Array.prototype.filter.call(document.querySelectorAll('img'), function (image) {
      return !!imageUrl(image);
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
      if (image && imageUrl(image)) {
        onImageClick(event, image);
        return;
      }
      var video = target.closest('video');
      if (video && onVideoClick(event, video)) return;
      var pick = target.closest('[data-libra-pick-images]');
      if (pick) {
        event.preventDefault();
        event.stopPropagation();
        /* The injected button is an acquisition affordance, not a page
         * upload action.  Trigger the editor's existing file input directly
         * so WebView dispatches onShowFileChooser() and the native
         * PictureSelector flow owns selection, crop, and compression. */
        if (pick.getAttribute('data-libra-native-picker') === 'true') {
          var nativeFileInput = fallbackImageInput(pick);
          if (nativeFileInput) nativeFileInput.click();
          return;
        }
        /* The ticket is intentionally supplied by the page's same-origin
         * authenticated code and is never persisted by this script. */
        var ticketOwner = pick.closest('[data-upload-ticket]');
        var ticket = ticketOwner ? ticketOwner.getAttribute('data-upload-ticket') : null;
        var editor = pick.closest('.w-md-editor');
        var requestId = ticket ? uuid() : null;
        if (requestId) rememberUploadTarget(requestId, editor);
        var handled = !!(ticket && requestId && emit(
          'pick_and_upload_images',
          { uploadTicket: ticket },
          requestId
        ));
        if (!handled && requestId) forgetUploadTarget(requestId);
        if (!handled) {
          /* Page-owned buttons may use the old file-input fallback when the
           * App upload action is unavailable. */
          var fileInput = fallbackImageInput(pick);
          if (fileInput) fileInput.click();
        }
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
      if (next && next.origin === SITE_ORIGIN && hasUserActivation()) {
        var handled = emit(isPostUrl(next) ? 'open_post' : 'open_page', { url: next.href });
        if (handled) return undefined;
      }
      return original.apply(window.history, arguments);
    };
  }

  interceptHistory('pushState');
  interceptHistory('replaceState');

  function fallbackImageInput(pick) {
    var editor = pick.closest('.w-md-editor');
    if (!editor) return null;
    var scope = editor;
    while (scope && scope !== document.body) {
      var inputs = scope.querySelectorAll('input[type="file"]');
      for (var i = 0; i < inputs.length; i++) {
        var accept = inputs[i].getAttribute('accept') || '';
        if (!accept || accept.toLowerCase().indexOf('image') !== -1) return inputs[i];
      }
      scope = scope.parentElement;
    }
    return null;
  }

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
  window.LibraNativeBridge = publicApi;

  wire(document);
  installReplyListener();
  injectPicker();
  if (window.MutationObserver) {
    new MutationObserver(injectPicker).observe(document.documentElement, { childList: true, subtree: true });
  }
})();
