const $ = (id) => document.getElementById(id);
let currentUrl = "";
let currentVideo = null;
let mode = "video";
let pollTimer = null;

// I njëjti UI punon në dy vende: në web (me serverin Flask) dhe në app-in
// Android, ku shkarkimi bëhet në telefon përmes `window.AndroidBridge`.
const api = window.AndroidBridge ? androidApi(window.AndroidBridge) : webApi();

// ---- Ruajtja lokale (cilësimet; historiku në web) ----

function load(key, fallback) {
  try {
    const value = JSON.parse(localStorage.getItem(key));
    return value ?? fallback;
  } catch {
    return fallback;
  }
}

function save(key, value) {
  try {
    localStorage.setItem(key, JSON.stringify(value));
  } catch {
    // Pa localStorage cilësimet thjesht nuk mbahen mend.
  }
}

const DEFAULT_SETTINGS = { height: "max", vcontainer: "mp4", acontainer: "mp3", bitrate: "192" };
let settings = { ...DEFAULT_SETTINGS, ...load("shkarko.settings", {}) };

// ---- Platformat ----

function webApi() {
  async function json(res) {
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || "Gabim i panjohur.");
    return data;
  }
  return {
    info: (url) => fetch(`/api/info?url=${encodeURIComponent(url)}`).then(json),
    download: (payload) =>
      fetch("/api/download", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      }).then(json),
    async progress(jobId) {
      const res = await fetch(`/api/progress/${jobId}`);
      return res.ok ? res.json() : null;
    },
    finished(jobId, job, link, entry) {
      $("progress-text").textContent = `Gati: ${job.filename}`;
      link.href = `/api/file/${jobId}`;
      link.click();
      const library = load("shkarko.library", []);
      library.unshift({ ...entry, id: jobId, filename: job.filename, date: Date.now() });
      save("shkarko.library", library.slice(0, 200));
    },
    async library() {
      return load("shkarko.library", []);
    },
    open(entry) {
      // Serveri i mban skedarët 1 orë; pas kësaj linku nuk punon më.
      window.location.href = `/api/file/${entry.id}`;
    },
    async remove(entry) {
      save("shkarko.library", load("shkarko.library", []).filter((e) => e.id !== entry.id));
    },
    async platform() {
      return {
        folder: "Dosja e shkarkimeve e shfletuesit",
        note: "Shfletuesi vendos ku ruhen skedarët. Ndryshoje te cilësimet e shfletuesit.",
        canChooseFolder: false,
        about: "Versioni web.",
      };
    },
  };
}

function androidApi(bridge) {
  const pending = {};
  let nextId = 0;
  window.__androidCallback = (callId, result) => {
    const { resolve, reject } = pending[callId];
    delete pending[callId];
    result.error ? reject(new Error(result.error)) : resolve(result);
  };
  const call = (method, ...args) =>
    new Promise((resolve, reject) => {
      const callId = String(nextId++);
      pending[callId] = { resolve, reject };
      bridge[method](callId, ...args);
    });
  const parse = (text) => {
    const data = JSON.parse(text);
    if (data.error) throw new Error(data.error);
    return data;
  };
  return {
    info: (url) => call("getInfo", url),
    download: async (payload) => parse(bridge.startDownload(JSON.stringify(payload))),
    progress: async (jobId) => JSON.parse(bridge.progress(jobId)),
    finished(jobId, job, link) {
      $("progress-text").textContent = `U ruajt te ${job.location}: ${job.filename}`;
      link.textContent = "Hape skedarin";
      link.removeAttribute("href");
      link.onclick = () => bridge.openDownload(jobId);
    },
    library: async () => JSON.parse(bridge.listDownloads()),
    open: (entry) => bridge.openDownload(entry.id),
    remove: async (entry) => parse(bridge.deleteDownload(entry.id)),
    platform: async () => JSON.parse(bridge.getSettings()),
    chooseFolder: () => call("chooseFolder"),
    resetFolder: async () => JSON.parse(bridge.resetFolder()),
  };
}

// ---- Ndihmës ----

function showError(msg) {
  $("error").textContent = msg;
  $("error").hidden = !msg;
}

function formatSize(bytes) {
  if (!bytes) return "";
  const units = ["B", "KB", "MB", "GB"];
  let i = 0;
  while (bytes >= 1024 && i < units.length - 1) { bytes /= 1024; i++; }
  return `~${bytes.toFixed(i > 1 ? 1 : 0)} ${units[i]}`;
}

function formatDuration(sec) {
  if (!sec) return "";
  sec = Math.round(sec);
  const h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60), s = sec % 60;
  const pad = (n) => String(n).padStart(2, "0");
  return h ? `${h}:${pad(m)}:${pad(s)}` : `${m}:${pad(s)}`;
}

function formatDate(ms) {
  const d = new Date(ms);
  const pad = (n) => String(n).padStart(2, "0");
  return `${pad(d.getDate())}.${pad(d.getMonth() + 1)}.${d.getFullYear()}`;
}

function escapeHtml(text) {
  const div = document.createElement("div");
  div.textContent = text ?? "";
  return div.innerHTML;
}

function checked(name) {
  const el = document.querySelector(`input[name="${name}"]:checked`);
  return el ? el.value : null;
}

function check(name, value) {
  const el = document.querySelector(`input[name="${name}"][value="${value}"]`);
  if (el) el.checked = true;
}

// ---- Shiriti poshtë ----

function showView(name) {
  document.querySelectorAll(".view").forEach((v) => { v.hidden = v.id !== `view-${name}`; });
  document.querySelectorAll(".nav-btn").forEach((b) => b.classList.toggle("active", b.dataset.view === name));
  window.scrollTo(0, 0);
  if (name === "library") renderLibrary();
  if (name === "settings") renderSettings();
}

document.querySelectorAll(".nav-btn").forEach((btn) => {
  btn.addEventListener("click", () => showView(btn.dataset.view));
});

// ---- Shkarko ----

function renderResolutions(list) {
  const box = $("resolutions");
  box.innerHTML = "";
  // Zgjidhet rezolucioni më i lartë që nuk e kalon atë të parazgjedhurin.
  const limit = settings.height === "max" ? Infinity : Number(settings.height);
  const preferred = list.find((r) => r.height <= limit) || list[list.length - 1];
  list.forEach((r) => {
    const label = document.createElement("label");
    label.className = "res";
    const fps = r.fps > 30 ? r.fps : "";
    label.innerHTML = `
      <input type="radio" name="height" value="${r.height}" ${r === preferred ? "checked" : ""}>
      <span>
        <strong>${r.height}p${fps}</strong>
        ${r.label ? `<em>${r.label}</em>` : ""}
        <small>${formatSize(r.size)}</small>
      </span>`;
    box.appendChild(label);
  });
  if (!list.length) box.textContent = "Nuk u gjet asnjë rezolucion video.";
}

function applyDefaults() {
  check("vcontainer", settings.vcontainer);
  check("acontainer", settings.acontainer);
  check("bitrate", settings.bitrate);
  $("bitrate-row").hidden = settings.acontainer !== "mp3";
}

$("url-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  showError("");
  $("video").hidden = true;
  const btn = $("fetch-btn");
  btn.disabled = true;
  btn.textContent = "Duke kërkuar...";
  try {
    const url = $("url").value.trim();
    const data = await api.info(url);
    currentUrl = url;
    currentVideo = data;
    $("thumb").src = data.thumbnail || "";
    $("thumb").hidden = !data.thumbnail;
    $("title").textContent = data.title;
    $("meta").textContent = [data.uploader, formatDuration(data.duration)].filter(Boolean).join(" · ");
    renderResolutions(data.resolutions);
    applyDefaults();
    resetProgress();
    $("video").hidden = false;
  } catch (err) {
    showError(err.message);
  } finally {
    btn.disabled = false;
    btn.textContent = "Kërko";
  }
});

document.querySelectorAll(".tab").forEach((tab) => {
  tab.addEventListener("click", () => {
    document.querySelectorAll(".tab").forEach((t) => t.classList.toggle("active", t === tab));
    mode = tab.dataset.mode;
    $("video-options").hidden = mode !== "video";
    $("audio-options").hidden = mode !== "audio";
  });
});

document.querySelectorAll('input[name="acontainer"]').forEach((input) => {
  input.addEventListener("change", () => {
    $("bitrate-row").hidden = input.value !== "mp3";
  });
});

function resetProgress() {
  clearInterval(pollTimer);
  $("progress").hidden = true;
  $("save-link").hidden = true;
  $("bar-fill").style.width = "0%";
  $("download-btn").disabled = false;
}

$("download-btn").addEventListener("click", async () => {
  showError("");
  resetProgress();
  const payload = {
    url: currentUrl,
    mode,
    title: currentVideo?.title || "",
    thumbnail: currentVideo?.thumbnail || "",
  };
  if (mode === "video") {
    payload.height = checked("height");
    payload.container = checked("vcontainer");
    if (!payload.height) return showError("Zgjidhni një rezolucion.");
    payload.quality = `${payload.height}p`;
  } else {
    payload.container = checked("acontainer");
    payload.bitrate = checked("bitrate");
    payload.quality = payload.container === "mp3" ? `${payload.bitrate} kbps` : "Audio";
  }

  $("download-btn").disabled = true;
  $("progress").hidden = false;
  $("progress-text").textContent = "Duke filluar...";
  try {
    const data = await api.download(payload);
    const entry = { title: payload.title, thumbnail: payload.thumbnail, format: payload.container, quality: payload.quality };
    pollTimer = setInterval(() => poll(data.job_id, entry), 1000);
  } catch (err) {
    showError(err.message);
    resetProgress();
  }
});

async function poll(jobId, entry) {
  const job = await api.progress(jobId);
  if (!job) return;
  const pct = job.percent ?? 0;
  $("bar-fill").style.width = `${pct}%`;

  if (job.status === "downloading") {
    const parts = [`Duke shkarkuar${job.stream > 1 ? " audion" : ""}: ${pct.toFixed ? pct.toFixed(1) : pct}%`];
    if (job.speed) parts.push(`${formatSize(job.speed).replace("~", "")}/s`);
    if (job.eta) parts.push(`edhe ${formatDuration(job.eta)}`);
    $("progress-text").textContent = parts.join(" · ");
  } else if (job.status === "processing") {
    $("progress-text").textContent = "Duke e përpunuar skedarin (ffmpeg)...";
  } else if (job.status === "finished") {
    clearInterval(pollTimer);
    const link = $("save-link");
    link.hidden = false;
    $("download-btn").disabled = false;
    api.finished(jobId, job, link, entry);
  } else if (job.status === "error") {
    clearInterval(pollTimer);
    showError(job.error);
    $("download-btn").disabled = false;
  }
}

// ---- Shkarkimet ----

async function renderLibrary() {
  const list = $("library");
  const entries = await api.library();
  $("library-empty").hidden = entries.length > 0;
  list.innerHTML = "";
  entries.forEach((entry) => {
    const li = document.createElement("li");
    const meta = [
      (entry.format || "").toUpperCase(),
      entry.quality,
      entry.size ? formatSize(entry.size).replace("~", "") : "",
      entry.date ? formatDate(entry.date) : "",
    ].filter(Boolean).join(" · ");
    li.innerHTML = `
      ${entry.thumbnail ? `<img src="${escapeHtml(entry.thumbnail)}" alt="">` : `<div class="ph"></div>`}
      <div class="info">
        <p class="name">${escapeHtml(entry.title || entry.filename)}</p>
        <p class="meta">${escapeHtml(meta)}</p>
        ${entry.missing ? `<p class="meta missing">Skedari nuk gjendet më në telefon.</p>` : ""}
        <div class="actions">
          ${entry.missing ? "" : `<button type="button" class="small-btn open">Hape</button>`}
          <button type="button" class="small-btn secondary remove">Fshi</button>
        </div>
      </div>`;
    li.querySelector(".open")?.addEventListener("click", () => api.open(entry));
    li.querySelector(".remove").addEventListener("click", async () => {
      const question = window.AndroidBridge
        ? `Ta fshij "${entry.title || entry.filename}" edhe nga telefoni?`
        : `Ta heq "${entry.title || entry.filename}" nga lista?`;
      if (!confirm(question)) return;
      try {
        await api.remove(entry);
      } catch (err) {
        alert(err.message);
      }
      renderLibrary();
    });
    list.appendChild(li);
  });
}

// ---- Cilësimet ----

async function renderSettings() {
  const info = await api.platform();
  $("folder-label").textContent = info.folder;
  $("folder-note").textContent = info.note || "";
  $("folder-note").hidden = !info.note;
  $("folder-actions").hidden = !info.canChooseFolder;
  $("reset-folder").hidden = !info.customFolder;
  $("about").textContent = info.about || "";
  check("def-height", settings.height);
  check("def-vcontainer", settings.vcontainer);
  check("def-acontainer", settings.acontainer);
  check("def-bitrate", settings.bitrate);
}

["height", "vcontainer", "acontainer", "bitrate"].forEach((key) => {
  document.querySelectorAll(`input[name="def-${key}"]`).forEach((input) => {
    input.addEventListener("change", () => {
      settings[key] = input.value;
      save("shkarko.settings", settings);
      applyDefaults();
    });
  });
});

$("choose-folder").addEventListener("click", async () => {
  try {
    await api.chooseFolder();
  } catch (err) {
    if (err.message !== "cancelled") alert(err.message);
  }
  renderSettings();
});

$("reset-folder").addEventListener("click", async () => {
  await api.resetFolder();
  renderSettings();
});

applyDefaults();
