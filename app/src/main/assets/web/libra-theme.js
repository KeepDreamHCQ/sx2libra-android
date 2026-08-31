/* Small, side-effect-free theme detector shared by the document bridge and
 * the evaluateJavascript fallback. It never reads cookies or page storage. */
(function () {
  'use strict';

  var API_NAME = 'LibraThemeDetector';
  var DARK_TOKENS = {
    dark: true,
    'dark-mode': true,
    night: true,
    'night-mode': true,
    'theme-dark': true,
    black: true,
    dracula: true,
    business: true,
    coffee: true,
    halloween: true,
    forest: true,
    synthwave: true,
    luxury: true,
    dim: true,
    sunset: true
  };
  var LIGHT_TOKENS = {
    light: true,
    'light-mode': true,
    day: true,
    'day-mode': true,
    'theme-light': true,
    cupcake: true,
    corporate: true,
    emerald: true,
    retro: true,
    valentine: true,
    garden: true,
    aqua: true,
    pastel: true,
    wireframe: true,
    cmyk: true,
    lemonade: true,
    winter: true,
    nord: true,
    caramellatte: true,
    silk: true
  };

  function tokens(value) {
    if (typeof value !== 'string') return [];
    return value.toLowerCase().split(/\s+/).filter(function (item) {
      return item.length > 0;
    });
  }

  function modeFromTokens(value) {
    var values = tokens(value);
    var hasDark = false;
    var hasLight = false;
    for (var i = 0; i < values.length; i++) {
      if (DARK_TOKENS[values[i]]) hasDark = true;
      if (LIGHT_TOKENS[values[i]]) hasLight = true;
    }
    if (hasDark === hasLight) return null;
    return hasDark ? 'dark' : 'light';
  }

  function modeFromScheme(value) {
    var values = tokens(value && value.replace(/[,;]/g, ' '));
    var hasDark = values.indexOf('dark') >= 0;
    var hasLight = values.indexOf('light') >= 0;
    if (hasDark === hasLight) return null;
    return hasDark ? 'dark' : 'light';
  }

  function explicitModeForNode(node) {
    if (!node || node.nodeType !== 1) return null;
    var mode = modeFromTokens(node.getAttribute('data-theme'));
    if (mode) return mode;
    mode = modeFromTokens(node.getAttribute('data-color-scheme'));
    if (mode) return mode;

    var classValue = node.getAttribute('class');
    mode = modeFromTokens(classValue);
    if (mode) return mode;

    var inlineStyle = node.getAttribute('style') || '';
    var schemeMatch = inlineStyle.match(/(?:^|;)\s*color-scheme\s*:\s*([^;]+)/i);
    mode = modeFromScheme(schemeMatch && schemeMatch[1]);
    if (mode) return mode;

    try {
      var computed = window.getComputedStyle(node);
      mode = modeFromScheme(computed.getPropertyValue('color-scheme'));
      if (mode) return mode;
    } catch (_) {}
    return null;
  }

  function explicitMode() {
    var root = document.documentElement;
    var body = document.body;
    var mode = explicitModeForNode(root);
    if (mode) return mode;
    mode = explicitModeForNode(body);
    if (mode) return mode;

    try {
      var meta = document.querySelector('meta[name="color-scheme"]');
      return modeFromScheme(meta && meta.getAttribute('content'));
    } catch (_) {
      return null;
    }
  }

  function parseColor(value) {
    if (typeof value !== 'string' || value === 'transparent') return null;
    var match = value.match(/^rgba?\(\s*([0-9.]+)\s*,\s*([0-9.]+)\s*,\s*([0-9.]+)(?:\s*,\s*([0-9.]+))?\s*\)$/i);
    if (!match) return null;
    var alpha = match[4] == null ? 1 : parseFloat(match[4]);
    if (!isFinite(alpha) || alpha < 0.75) return null;
    return {
      red: Math.max(0, Math.min(255, parseFloat(match[1]))),
      green: Math.max(0, Math.min(255, parseFloat(match[2]))),
      blue: Math.max(0, Math.min(255, parseFloat(match[3]))),
      alpha: alpha
    };
  }

  function channel(value) {
    var normalized = value / 255;
    return normalized <= 0.03928
      ? normalized / 12.92
      : Math.pow((normalized + 0.055) / 1.055, 2.4);
  }

  function luminance(color) {
    return 0.2126 * channel(color.red) +
      0.7152 * channel(color.green) +
      0.0722 * channel(color.blue);
  }

  function ignored(element) {
    if (!element || !element.tagName) return true;
    var tag = element.tagName.toUpperCase();
    return tag === 'IMG' || tag === 'VIDEO' || tag === 'CANVAS' || tag === 'SVG';
  }

  function backgroundFor(element) {
    var current = element;
    while (current) {
      if (!ignored(current)) {
        try {
          var background = parseColor(window.getComputedStyle(current).backgroundColor);
          if (background) return background;
        } catch (_) {}
      }
      current = current.parentElement;
    }
    return null;
  }

  function elementAtPoint(x, y) {
    var elements = [];
    try {
      if (typeof document.elementsFromPoint === 'function') {
        elements = document.elementsFromPoint(x, y) || [];
      } else {
        var single = document.elementFromPoint(x, y);
        if (single) elements = [single];
      }
    } catch (_) {}
    for (var i = 0; i < elements.length; i++) {
      if (!ignored(elements[i])) return elements[i];
    }
    return elements.length ? elements[elements.length - 1].parentElement : null;
  }

  function visualMode() {
    var width = window.innerWidth || document.documentElement.clientWidth || 0;
    var height = window.innerHeight || document.documentElement.clientHeight || 0;
    if (width < 1 || height < 1) return 'unknown';

    var validSamples = 0;
    var darkSamples = 0;
    var lightSamples = 0;
    var textSamples = 0;
    var lightTextSamples = 0;
    var darkTextSamples = 0;
    var columns = 5;
    var rows = 7;

    for (var row = 0; row < rows; row++) {
      for (var column = 0; column < columns; column++) {
        var x = Math.min(width - 1, Math.floor(width * (column + 0.5) / columns));
        var y = Math.min(height - 1, Math.floor(height * (row + 0.5) / rows));
        var element = elementAtPoint(x, y);
        var background = backgroundFor(element);
        if (!background) continue;
        validSamples++;
        var backgroundLuminance = luminance(background);
        if (backgroundLuminance <= 0.35) darkSamples++;
        if (backgroundLuminance >= 0.65) lightSamples++;

        try {
          var foreground = parseColor(window.getComputedStyle(element).color);
          if (foreground) {
            textSamples++;
            var foregroundLuminance = luminance(foreground);
            if (foregroundLuminance >= 0.55) lightTextSamples++;
            if (foregroundLuminance <= 0.35) darkTextSamples++;
          }
        } catch (_) {}
      }
    }

    if (validSamples < 8) return 'unknown';
    var darkRatio = darkSamples / validSamples;
    var lightRatio = lightSamples / validSamples;
    var hasLightText = textSamples === 0 || lightTextSamples / textSamples >= 0.45;
    var hasDarkText = textSamples === 0 || darkTextSamples / textSamples >= 0.45;
    if (darkRatio >= 0.60 && hasLightText) return 'dark';
    if (lightRatio >= 0.60 && hasDarkText) return 'light';
    return 'unknown';
  }

  function detect() {
    try {
      return explicitMode() || visualMode();
    } catch (_) {
      return 'unknown';
    }
  }

  window[API_NAME] = { detect: detect };
})();
