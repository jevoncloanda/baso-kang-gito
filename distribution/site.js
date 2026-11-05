export function releaseDownload(policy) {
  if (!policy || policy.downloadUrl === null) return null;
  if (!Number.isSafeInteger(policy.latestVersionCode) || policy.latestVersionCode < 1 || !/^[0-9]+(?:\.[0-9]+){1,3}$/.test(policy.latestVersionName) ||
      !/^[a-f0-9]{64}$/.test(policy.sha256 || '') || !/^20\d{2}-\d{2}-\d{2}$/.test(policy.releaseDate || '')) throw Error('Invalid release');
  const expected = `/releases/baso-kang-gito-v${policy.latestVersionName}-${policy.latestVersionCode}.apk`;
  if (policy.downloadUrl !== 'https://basokanggito.jevoncl.com' + expected) throw Error('Untrusted download');
  return expected;
}

async function showRelease() {
  const status = document.getElementById('status');
  try {
    const response = await fetch('/version.json', {cache: 'no-store', signal: AbortSignal.timeout(12000)});
    if (!response.ok) throw Error('Version unavailable');
    const policy = await response.json();
    const path = releaseDownload(policy);
    if (!path) { status.textContent = 'APK resmi belum tersedia untuk diunduh. Silakan periksa kembali nanti.'; return; }
    document.getElementById('version').textContent = policy.latestVersionName;
    const date = new Date(policy.releaseDate + 'T00:00:00Z');
    if (!Number.isFinite(date.getTime()) || date.toISOString().slice(0, 10) !== policy.releaseDate) throw Error('Invalid release date');
    document.getElementById('date').textContent = new Intl.DateTimeFormat('id-ID', {dateStyle:'long',timeZone:'Asia/Jakarta'}).format(date);
    document.getElementById('details').hidden = false;
    const download = document.getElementById('download'); download.href = path; download.hidden = false;
    download.setAttribute('aria-describedby', 'download-help');
    const notes = document.getElementById('notes');
    if (!Array.isArray(policy.releaseNotes) || !policy.releaseNotes.every(note => typeof note === 'string')) throw Error('Invalid notes');
    for (const note of policy.releaseNotes) { const li = document.createElement('li'); li.textContent = note; notes.append(li); }
    document.getElementById('notes-panel').hidden = !policy.releaseNotes.length;
    status.textContent = '';
    status.hidden = true;
  } catch {
    document.getElementById('download').hidden = true;
    document.getElementById('details').hidden = true;
    status.textContent = 'Informasi pembaruan belum dapat dimuat. Periksa koneksi, lalu muat ulang halaman ini.';
  }
}
if (typeof document !== 'undefined') showRelease();
