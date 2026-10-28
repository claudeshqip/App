const $ = (id) => document.getElementById(id);
let currentUrl = "";
let mode = "video";
let pollTimer = null;

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
  const h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60), s = sec % 60;
  const pad = (n) => String(n).padStart(2, "0");
  return h ? `${h}:${pad(m)}:${pad(s)}` : `${m}:${pad(s)}`;
}

function renderResolutions(list) {
  const box = $("resolutions");
  box.innerHTML = "";
  list.forEach((r, i) => {
    const label = document.createElement("label");
    label.className = "res";
    const fps = r.fps > 30 ? r.fps : "";
    label.innerHTML = `
      <input type="radio" name="height" value="${r.height}" ${i === 0 ? "checked" : ""}>
      <span>
        <strong>${r.height}p${fps}</strong>
        ${r.label ? `<em>${r.label}</em>` : ""}
        <small>${formatSize(r.size)}</small>
      </span>`;
    box.appendChild(label);
  });
  if (!list.length) box.textContent = "Nuk u gjet asnjë rezolucion video.";
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
    const res = await fetch(`/api/info?url=${encodeURIComponent(url)}`);
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || "Gabim i panjohur.");
    currentUrl = url;
    $("thumb").src = data.thumbnail || "";
    $("thumb").hidden = !data.thumbnail;
    $("title").textContent = data.title;
    $("meta").textContent = [data.uploader, formatDuration(data.duration)].filter(Boolean).join(" · ");
    renderResolutions(data.resolutions);
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

function checked(name) {
  const el = document.querySelector(`input[name="${name}"]:checked`);
  return el ? el.value : null;
}

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
  const payload = { url: currentUrl, mode };
  if (mode === "video") {
    payload.height = checked("height");
    payload.container = checked("vcontainer");
    if (!payload.height) return showError("Zgjidhni një rezolucion.");
  } else {
    payload.container = checked("acontainer");
    payload.bitrate = checked("bitrate");
  }

  $("download-btn").disabled = true;
  $("progress").hidden = false;
  $("progress-text").textContent = "Duke filluar...";
  try {
    const res = await fetch("/api/download", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || "Gabim i panjohur.");
    pollTimer = setInterval(() => poll(data.job_id), 1000);
  } catch (err) {
    showError(err.message);
    resetProgress();
  }
});

async function poll(jobId) {
  const res = await fetch(`/api/progress/${jobId}`);
  if (!res.ok) return;
  const job = await res.json();
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
    $("progress-text").textContent = `Gati: ${job.filename}`;
    const link = $("save-link");
    link.href = `/api/file/${jobId}`;
    link.hidden = false;
    $("download-btn").disabled = false;
    link.click();
  } else if (job.status === "error") {
    clearInterval(pollTimer);
    showError(job.error);
    $("download-btn").disabled = false;
  }
}
