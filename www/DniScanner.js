'use strict';

var exec = require('cordova/exec');

function normalizeError(error) {
    if (error && typeof error === 'object' && error.code) {
        return error;
    }
    if (typeof error === 'string') {
        try {
            var parsed = JSON.parse(error);
            if (parsed && parsed.code) {
                return parsed;
            }
        } catch (ignored) {
            // Keep the original message below.
        }
        return { code: 'UNKNOWN', message: error, recoverable: false };
    }
    return { code: 'UNKNOWN', message: 'Unknown DNI scanner error.', recoverable: false };
}

/**
 * Opens the native automatic DNI or generic ID-1 card scanner.
 *
 * @param {{profile?: ('dni'|'genericCard'), side: ('front'|'back'|'any'),
 *          autoCapture?: boolean,
 *          sideValidation?: ('off'|'advisory'|'strict'),
 *          flashMode?: ('off'|'auto'|'on'), showCloseButton?: boolean,
 *          showFlashButton?: boolean, debugMode?: boolean, beepEnabled?: boolean,
 *          preferredCaptureLongEdge?: number, maxOutputLongEdge?: number,
 *          instructionText?: string,
 *          checks?: {documentSize?: boolean, centering?: boolean,
 *          perspective?: boolean, stability?: boolean, sharpness?: boolean,
 *          brightness?: boolean, glare?: boolean, shadows?: boolean,
 *          edgeOcclusion?: boolean},
 *          orientation?: ('portrait'|'landscape'|'auto'), timeoutMs?: number}} options
 * @returns {Promise<object>} A success result or {status: 'cancelled', reason: 'user'}.
 */
exports.scan = function scan(options) {
    var scanOptions = options || {};
    return new Promise(function (resolve, reject) {
        exec(resolve, function (rawError) {
            var error = normalizeError(rawError);
            if (error.code === 'USER_CANCELLED') {
                resolve({ status: 'cancelled', reason: 'user' });
                return;
            }
            reject(error);
        }, 'DniScanner', 'scan', [scanOptions]);
    });
};
