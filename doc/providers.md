# Providers

This page lists where the common WebDAV servers and sync clients keep
the folders that a store can sync through, and what each browser can do
with a folder. These are facts about other products that change, so
like-minded contains none of them, and each section says when they were
checked. To make sure that a URL or a path still works, use
`store/check!`.


## WebDAV servers, as of September 2026

| Server | The URL of the collection | The credentials |
|---|---|---|
| Nextcloud | `https://HOST/remote.php/dav/files/USER/FOLDER/` | An app password, from Settings, Security, Devices & sessions |
| ownCloud | `https://HOST/remote.php/dav/files/USER/FOLDER/` | An app password, from Settings, Security |
| Seafile | `https://HOST/seafdav/LIBRARY/FOLDER/` | The WebDAV password, set in Settings |
| Synology DiskStation | `https://HOST:5006/SHARED-FOLDER/FOLDER/` | The password of the account. The WebDAV Server package must be on. |
| Fastmail | `https://myfiles.fastmail.com/FOLDER/` | An app password for Files (WebDAV). The user name is the email address. |
| Hetzner Storage Box | `https://BOX.your-storagebox.de/FOLDER/` | The password of the box. WebDAV must be on, in the Robot. |
| Box | `https://dav.box.com/dav/FOLDER/` | The password of the account, or an app password if two-step verification is on |

If a user name is an email address, percent-encode it in the path, e.g.
`simon%40example.org`.


## Sync clients, as of September 2026

| Sync client | macOS | Windows | Linux |
|---|---|---|---|
| iCloud Drive | `~/Library/Mobile Documents/com~apple~CloudDocs/` | `%USERPROFILE%\iCloudDrive\` | |
| Dropbox | `~/Dropbox/` | `%USERPROFILE%\Dropbox\` | `~/Dropbox/` |
| Google Drive | `~/Library/CloudStorage/GoogleDrive-EMAIL/My Drive/` | A drive letter, `G:\My Drive\` by default | |
| OneDrive | `~/Library/CloudStorage/OneDrive-Personal/` | `%USERPROFILE%\OneDrive\` | |
| Seafile | `~/Seafile/` | `%USERPROFILE%\Seafile\` | `~/Seafile/` |
| Nextcloud | `~/Nextcloud/` | `%USERPROFILE%\Nextcloud\` | `~/Nextcloud/` |
| Syncthing | The folder that you set up | | |

With "Optimize Mac Storage" on, iCloud Drive removes files that haven't
been used for a while and leaves a hidden `.name.icloud` placeholder. The
folder store ignores hidden files, so it can't read such a document until
iCloud Drive downloads it again.


## Folders in a browser, as of October 2026

Which browser can do what changes over time, so check for the feature
itself, e.g. `(exists? js/showDirectoryPicker)`, rather than for a
browser. The versions are from MDN's compatibility data of 1 October 2026,
and the times were measured on 3 October 2026 in Chrome 152, Firefox 156
and Safari 26.3.

| | Chrome, Edge | Firefox | Safari |
|---|---|---|---|
| Pick a folder, `showDirectoryPicker` | 86, and 132 on Android | No | No |
| Ask for a folder again, `requestPermission` | 86. From 122, the user can allow a site on every visit. | No | No |
| Write a file, `createWritable` | 86 | 111, in the origin private file system | 26, in the origin private file system |
| Load a folder, `<input webkitdirectory>` | Yes | Yes | Yes, and on iOS from 18.4 |
| Modification times in the origin private file system | Milliseconds | Milliseconds | Whole seconds |

Mozilla and WebKit have both stated positions against picking folders.
So for a while, in Firefox and Safari, the user syncs through a folder by
hand: they save the document that `document/for-device` gives into the
folder, and load the folder with `<input webkitdirectory>` to read the
documents of the other devices.

Since Safari keeps whole seconds, two writes of the same size within a
second there get the same version. Each device writes only its own
document, and rarely twice in a second, so the default strategy of a
document per device is hardly affected. The shared strategy is, since its
writes depend on the version.
