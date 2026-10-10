/*
 * YunX (云析) - A network drive share-link parser and high-speed downloader for Android.
 * Copyright (C) 2026 CYQawa
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.yunx.app.data.security

/**
 * Encrypted hand-off for short-lived drive authorization headers used by Dsu Manager's aria2c service.
 * The ciphertext may be persisted with a download task; raw cookies are never written to task storage.
 */
object DownloadRequestVault {
    private const val PURPOSE = "dsu_aria2_download_headers_v1"

    @JvmStatic
    fun encryptHeaders(json: String): String =
        AndroidKeystoreCredentialCipher.shared.encrypt(json, PURPOSE)

    @JvmStatic
    fun decryptHeaders(ciphertext: String): String =
        AndroidKeystoreCredentialCipher.shared.decrypt(ciphertext, PURPOSE)
}
