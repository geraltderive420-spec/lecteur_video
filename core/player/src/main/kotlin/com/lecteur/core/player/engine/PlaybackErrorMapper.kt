package com.lecteur.core.player.engine

import androidx.media3.common.PlaybackException

/** Turns Media3 error codes into messages a user can act on. */
object PlaybackErrorMapper {

    fun map(errorCode: Int, rawMessage: String?): PlaybackError = when (errorCode) {
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
            PlaybackError(
                PlaybackErrorKind.FILE_MISSING,
                "Le fichier est introuvable.",
                "Vérifiez que le disque, la carte SD ou la clé USB est bien branché, puis relancez la lecture.",
                rawMessage
            )

        PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
            PlaybackError(
                PlaybackErrorKind.NO_PERMISSION,
                "L'application n'a plus l'autorisation de lire ce fichier.",
                "Rouvrez le fichier ou rajoutez son dossier dans les réglages pour redonner l'accès.",
                rawMessage
            )

        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
            PlaybackError(
                PlaybackErrorKind.UNSUPPORTED_FORMAT,
                "Cet appareil ne parvient pas à décoder ce format vidéo ou audio.",
                "Essayez le décodage logiciel dans les réglages du lecteur.",
                rawMessage
            )

        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
            PlaybackError(
                PlaybackErrorKind.CORRUPT_FILE,
                "Le fichier est endommagé ou son format n'est pas reconnu.",
                "Retéléchargez le fichier ou essayez-le dans un autre lecteur.",
                rawMessage
            )

        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            PlaybackError(
                PlaybackErrorKind.NETWORK,
                "La connexion a été interrompue.",
                "Vérifiez le réseau puis réessayez.",
                rawMessage
            )

        else ->
            PlaybackError(
                PlaybackErrorKind.UNKNOWN,
                "La lecture a échoué.",
                "Réessayez, ou ouvrez le fichier avec une autre application.",
                rawMessage
            )
    }
}
