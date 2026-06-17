// SPDX-FileCopyrightText: 2019-2020 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import ltd.evilcorp.core.vo.FT_INTERRUPTED_BASE
import ltd.evilcorp.core.vo.FT_NOT_STARTED
import ltd.evilcorp.core.vo.FT_REJECTED
import ltd.evilcorp.core.vo.FileTransfer

@Dao
interface FileTransferDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun save(fileTransfer: FileTransfer): Long

    @Query("DELETE FROM file_transfers WHERE id == :id")
    fun delete(id: Int)

    @Query("SELECT * FROM file_transfers WHERE public_key == :publicKey")
    fun load(publicKey: String): Flow<List<FileTransfer>>

    @Query("SELECT * FROM file_transfers WHERE id == :id")
    fun load(id: Int): Flow<FileTransfer>

    @Query(
        """
        SELECT * FROM file_transfers
        WHERE public_key == :publicKey
            AND outgoing == 0
            AND progress <= :interruptedBase
            AND (
                file_id == :fileId
                OR (
                    file_id == ''
                    AND file_name == :fileName
                    AND file_size == :fileSize
                )
            )
        ORDER BY CASE WHEN file_id == :fileId THEN 0 ELSE 1 END, id DESC
        LIMIT 1
        """,
    )
    fun loadInterruptedIncoming(
        publicKey: String,
        fileId: String,
        fileName: String,
        fileSize: Long,
        interruptedBase: Long = FT_INTERRUPTED_BASE,
    ): FileTransfer?

    @Query(
        """
        SELECT * FROM file_transfers
        WHERE public_key == :publicKey
            AND outgoing == 1
            AND progress <= :interruptedBase
        """,
    )
    fun loadInterruptedOutgoing(publicKey: String, interruptedBase: Long = FT_INTERRUPTED_BASE): List<FileTransfer>

    @Query("UPDATE file_transfers SET progress = :progress WHERE id == :id AND progress != :rejected")
    fun updateProgress(id: Int, progress: Long, rejected: Long = FT_REJECTED)

    @Query("UPDATE file_transfers SET destination = :destination WHERE id == :id")
    fun setDestination(id: Int, destination: String)

    @Query("UPDATE file_transfers SET thumbnail = :thumbnail WHERE id == :id")
    fun setThumbnail(id: Int, thumbnail: String)

    @Query(
        """
        UPDATE file_transfers
        SET progress = CASE
            WHEN progress >= 0 THEN :interruptedBase - progress
            WHEN progress == :notStarted THEN :rejected
            ELSE progress
        END
        WHERE progress < file_size AND progress != :rejected AND progress > :interruptedBase
        """,
    )
    fun resetTransientData(
        interruptedBase: Long = FT_INTERRUPTED_BASE,
        notStarted: Long = FT_NOT_STARTED,
        rejected: Long = FT_REJECTED,
    )
}
