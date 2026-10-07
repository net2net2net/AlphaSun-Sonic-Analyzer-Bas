/* 最小 canvas 2D context 桩：让应用的绘图调用全部无副作用通过。
   缺它 → getContext 返回 null → resize() 抛错 → 整个内联脚本中断。 */
function makeCtxStub(getWin) {
  const gradient = { addColorStop() {} };
  return function () {
    return new Proxy({}, {
      get(t, k) {
        if (k === 'canvas') return { width: 300, height: 150, style: {} };
        if (k === 'measureText') return () => ({ width: 10, actualBoundingBoxAscent: 8 });
        if (k === 'getImageData') return () => ({ data: new Uint8ClampedArray(4) });
        if (k === 'createLinearGradient' || k === 'createRadialGradient' || k === 'createConicGradient') return () => gradient;
        if (k === 'createPattern') return () => null;
        if (typeof k === 'symbol') return undefined;
        // 其余方法（fillRect/beginPath/arc/setTransform/…）一律空实现
        return () => undefined;
      },
      set() { return true; }
    });
  };
}
module.exports = { makeCtxStub };
