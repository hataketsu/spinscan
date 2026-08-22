// 3D viewer for the reconstruction outputs: PLY point clouds, PLY meshes, STL.
import * as THREE from 'three';
import { OrbitControls } from '/static/vendor/OrbitControls.js';
import { PLYLoader } from '/static/vendor/PLYLoader.js';
import { STLLoader } from '/static/vendor/STLLoader.js';

const el = {
  root:  document.getElementById('viewer'),
  name:  document.getElementById('vName'),
  info:  document.getElementById('vInfo'),
  close: document.getElementById('vClose'),
  reset: document.getElementById('vReset'),
  color: document.getElementById('vColor'),
  size:  document.getElementById('vSize'),
  flip:  document.getElementById('vFlip'),
};

let renderer, scene, camera, controls, object, frame;
let vertexColor = true, pointScale = 1, radius = 1, flipped = false;
let baseQuat = new THREE.Quaternion();

// COLMAP's world frame has no gravity reference, so a cloud can come out lying on
// its side or upside down. The dominant plane in these scans is the sheet the
// object sits on: find it, stand it up as the ground, and put the object above it.
function autoOrient(geometry) {
  const pos = geometry.getAttribute('position');
  const n = pos.count;
  const step = Math.max(1, Math.floor(n / 20000));
  const pts = [];
  for (let i = 0; i < n; i += step) pts.push(new THREE.Vector3().fromBufferAttribute(pos, i));
  if (pts.length < 30) return { quat: new THREE.Quaternion(), ok: false };

  geometry.computeBoundingBox();
  const tol = geometry.boundingBox.getSize(new THREE.Vector3()).length() * 0.01;
  const ab = new THREE.Vector3(), ac = new THREE.Vector3(), nrm = new THREE.Vector3();
  let best = null, bestVotes = -1;
  for (let it = 0; it < 250; it++) {
    const a = pts[(Math.random() * pts.length) | 0];
    const b = pts[(Math.random() * pts.length) | 0];
    const c = pts[(Math.random() * pts.length) | 0];
    ab.subVectors(b, a); ac.subVectors(c, a);
    nrm.crossVectors(ab, ac);
    if (nrm.lengthSq() < 1e-16) continue;
    nrm.normalize();
    const d = nrm.dot(a);
    let votes = 0;
    for (let i = 0; i < pts.length; i++) if (Math.abs(nrm.dot(pts[i]) - d) < tol) votes++;
    if (votes > bestVotes) { bestVotes = votes; best = { n: nrm.clone(), d }; }
  }
  // A plane that almost nothing lies on is not a ground plane; leave it alone.
  if (!best || bestVotes < pts.length * 0.08) return { quat: new THREE.Quaternion(), ok: false };

  // Point the normal towards whichever side holds fewer points -- the object,
  // not the table it stands on.
  let above = 0, below = 0;
  for (let i = 0; i < pts.length; i++) {
    const h = best.n.dot(pts[i]) - best.d;
    if (h > tol) above++; else if (h < -tol) below++;
  }
  if (above > below) best.n.negate();

  return {
    quat: new THREE.Quaternion().setFromUnitVectors(best.n, new THREE.Vector3(0, 1, 0)),
    ok: true,
  };
}

function init() {
  renderer = new THREE.WebGLRenderer({ antialias: true, powerPreference: 'high-performance' });
  renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
  el.root.appendChild(renderer.domElement);

  scene = new THREE.Scene();
  scene.background = new THREE.Color(0x0B0F14);

  camera = new THREE.PerspectiveCamera(50, 1, 0.001, 5000);
  controls = new OrbitControls(camera, renderer.domElement);
  controls.enableDamping = true;
  controls.dampingFactor = 0.08;

  // Two lights, no shadows: enough to read a matte grey surface without hiding detail.
  scene.add(new THREE.HemisphereLight(0xdfe8f5, 0x1a2029, 2.0));
  const key = new THREE.DirectionalLight(0xffffff, 1.6);
  key.position.set(1, 2, 1.5);
  scene.add(key);

  addEventListener('resize', resize);
  el.close.onclick = close;
  el.reset.onclick = () => frameObject();
  el.color.onclick = () => { vertexColor = !vertexColor; el.color.classList.toggle('on', vertexColor); applyStyle(); };
  el.size.onclick  = () => { pointScale = pointScale === 1 ? 2.5 : 1; el.size.classList.toggle('on', pointScale > 1); applyStyle(); };
  el.flip.onclick  = () => {
    flipped = !flipped;
    el.flip.classList.toggle('on', flipped);
    applyFlip();
    frameObject();
  };
  addEventListener('keydown', e => { if (e.key === 'Escape' && el.root.classList.contains('on')) close(); });
}

function resize() {
  if (!renderer) return;
  const w = el.root.clientWidth, h = el.root.clientHeight;
  renderer.setSize(w, h, false);
  camera.aspect = w / h;
  camera.updateProjectionMatrix();
}

function clear() {
  if (!object) return;
  scene.remove(object);
  object.geometry?.dispose();
  object.material?.dispose();
  object = null;
}

// Turn the model over about its own centre, so it stays where the camera looks.
// Always rebuilt from baseQuat, otherwise toggling the flip off cannot undo it.
function applyFlip() {
  if (!object) return;
  object.position.set(0, 0, 0);
  object.quaternion.copy(baseQuat);
  if (flipped) {
    object.quaternion.premultiply(
      new THREE.Quaternion().setFromAxisAngle(new THREE.Vector3(1, 0, 0), Math.PI));
  }
  object.updateMatrixWorld(true);
}

function frameObject() {
  if (!object) return;
  const box = new THREE.Box3().setFromObject(object);
  const c = box.getCenter(new THREE.Vector3());
  radius = Math.max(box.getSize(new THREE.Vector3()).length() / 2, 1e-4);
  controls.target.copy(c);
  camera.position.copy(c).add(new THREE.Vector3(1, 0.65, 1).normalize().multiplyScalar(radius * 2.6));
  camera.near = radius / 500;
  camera.far = radius * 200;
  camera.updateProjectionMatrix();
  controls.update();
}

function applyStyle() {
  if (!object) return;
  const m = object.material;
  const hasColor = !!object.geometry.getAttribute('color');
  m.vertexColors = vertexColor && hasColor;
  m.color.set(m.vertexColors ? 0xffffff : (object.isPoints ? 0xFFB449 : 0xB9C4D2));
  if (object.isPoints) m.size = radius * 0.0035 * pointScale;
  m.needsUpdate = true;
}

function show(geometry, isMesh, counts) {
  clear();
  geometry.computeBoundingBox();
  if (isMesh) {
    if (!geometry.getAttribute('normal')) geometry.computeVertexNormals();
    object = new THREE.Mesh(geometry, new THREE.MeshStandardMaterial({
      color: 0xB9C4D2, roughness: 0.85, metalness: 0.0, side: THREE.DoubleSide, flatShading: false,
    }));
  } else {
    object = new THREE.Points(geometry, new THREE.PointsMaterial({ size: 1, sizeAttenuation: true }));
  }
  baseQuat = autoOrient(geometry).quat;
  applyFlip();
  scene.add(object);
  frameObject();
  applyStyle();
  el.size.style.display = isMesh ? 'none' : '';
  el.info.textContent = '';
  el.info.style.display = 'none';
  el.name.textContent = `${el.name.dataset.file} — ${counts}`;
}

export function openViewer(project, file) {
  if (!renderer) init();
  el.root.classList.add('on');
  el.name.dataset.file = file;
  el.name.textContent = file;
  el.info.style.display = '';
  el.info.textContent = 'Đang tải…';
  resize();
  animate();

  const url = `/api/projects/${encodeURIComponent(project)}/download/${encodeURIComponent(file)}`;
  const isSTL = file.toLowerCase().endsWith('.stl');
  const loader = isSTL ? new STLLoader() : new PLYLoader();
  loader.load(url, geo => {
    const n = geo.getAttribute('position').count;
    const isMesh = isSTL || !!geo.getIndex();
    show(geo, isMesh, isMesh
      ? `${(geo.index ? geo.index.count / 3 : n / 3).toLocaleString()} tam giác`
      : `${n.toLocaleString()} điểm`);
  },
  e => { if (e.lengthComputable) el.info.textContent = `Đang tải… ${Math.round(e.loaded / e.total * 100)}%`; },
  err => { el.info.textContent = 'Không đọc được file: ' + (err.message || err); });
}

function close() {
  el.root.classList.remove('on');
  cancelAnimationFrame(frame);
  frame = null;
  clear();
}

function animate() {
  frame = requestAnimationFrame(animate);
  controls.update();
  renderer.render(scene, camera);
}
