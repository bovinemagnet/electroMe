// Initialises any chart div that carries its own data. No fetching, no state.
//
// Colours arrive as "@token" references rather than literals, because the option
// object is JSON and ECharts cannot read CSS custom properties. Resolving them
// here against the stylesheet means one palette serves light and dark alike.
(function () {
  'use strict';

  var FORMATTERS = {
    '@kW': function (v) { return Number(v).toFixed(2) + ' kW'; },
    '@kWh': function (v) { return Number(v).toFixed(1) + ' kWh'; },
    '@dollars': function (v) { return '$' + Number(v).toFixed(2); },
    '@dollarLabel': function (p) { return '$' + Number(p.value).toFixed(0); }
  };

  function token(name) {
    return getComputedStyle(document.documentElement)
      .getPropertyValue('--' + name).trim();
  }

  // Walks the option tree replacing "@name" markers in place.
  function resolve(node) {
    if (Array.isArray(node)) {
      for (var i = 0; i < node.length; i++) {
        node[i] = resolve(node[i]);
      }
      return node;
    }
    if (node && typeof node === 'object') {
      for (var key in node) {
        if (Object.prototype.hasOwnProperty.call(node, key)) {
          node[key] = resolve(node[key]);
        }
      }
      return node;
    }
    if (typeof node === 'string' && node.charAt(0) === '@') {
      if (FORMATTERS[node]) { return FORMATTERS[node]; }
      var value = token(node.slice(1));
      return value || node;
    }
    return node;
  }

  function theme() {
    return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches
      ? 'dark' : null;
  }

  function render(root) {
    var nodes = (root || document).querySelectorAll('.chart[data-chart]');
    for (var i = 0; i < nodes.length; i++) {
      var node = nodes[i];
      if (node.dataset.rendered === 'true') { continue; }
      var option = resolve(JSON.parse(node.dataset.chart));
      var chart = echarts.init(node, theme(), { renderer: 'canvas' });
      chart.setOption(option);
      node.dataset.rendered = 'true';
      node.echartsInstance = chart;
    }
  }

  function forEachChart(fn) {
    var nodes = document.querySelectorAll('.chart[data-chart]');
    for (var i = 0; i < nodes.length; i++) {
      if (nodes[i].echartsInstance) { fn(nodes[i]); }
    }
  }

  document.addEventListener('DOMContentLoaded', function () { render(document); });
  document.body.addEventListener('htmx:afterSwap', function (e) { render(e.target); });
  window.addEventListener('resize', function () {
    forEachChart(function (n) { n.echartsInstance.resize(); });
  });

  // Follow a live colour-scheme change: dispose and re-init so the ECharts theme flips too.
  if (window.matchMedia) {
    window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', function () {
      forEachChart(function (n) {
        n.echartsInstance.dispose();
        n.echartsInstance = null;
        n.dataset.rendered = 'false';
      });
      render(document);
    });
  }
})();
