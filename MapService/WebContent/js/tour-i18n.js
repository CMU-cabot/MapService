/*******************************************************************************
 * Copyright (c) 2014, 2017 IBM Corporation, Carnegie Mellon University and
 * others
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 ******************************************************************************/
let Touri18n = (function () {
    let defaultLang = $hulop.messages.defaultLang == 'ja' ? 'ja' : 'en';
    let messages = {}, keynames = [];

    $.ajax({
        'type': 'get',
        'async': false,
        'url': `cabot/messages_${defaultLang}.json`,
        'dataType': 'json',
        'success': function (data) {
            messages = data.messages || {};
            keynames = data.keynames || [];
            console.log(`defaultlang ${defaultLang}`);
            console.log(data);
        },
        'error': function (XMLHttpRequest, textStatus, errorThrown) {
            console.error(textStatus + ' (' + XMLHttpRequest.status + '): ' + errorThrown);
        }
    });

    function translate(parent_selector) {
        keynames.forEach(item => {
            if (!parent_selector || parent_selector == item.parent_selector) {
                for (const [key, value] of Object.entries(item.keys)) {
                    let node = $(`${item.parent_selector} [${item.key_name}=${key.replaceAll(':', '\\:')}]`);
                    node.each((i, e) => {
                        let node = $(e);
                        if (!node.prop('_translated')) {
                            let label = node.prop('tagName') == 'TD' ? node.prev() : node.parent().prev();
                            // console.log(`${label.text()} => ${value.text}`);
                            label.contents().first()[0].nodeValue = value.text;
                            node.prop('title', value.help);
                            node.prop('_translated', true);
                        }
                    });
                }
            }
        });
    }

    function getMessage(key) {
        return messages[key] || `(${key});`
    }

    $(document).ready(() => {
        for (const [key, value] of Object.entries(messages)) {
            $(`[i18n=${key}]`).text(value);
        }
    });

    return {
        'translate': translate,
        '_': getMessage
    }
})();
