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
let MessageChecker = (function () {

    function show_message_type(type, messages) {
        $('<h3>', { 'text': type }).appendTo($('#check-messages'));
        for (const message of messages) {
            for (const [key, value] of Object.entries(message)) {
                if (key == 'type' && value == type) {
                    for (const [key, value] of Object.entries(message)) {
                        if (key != 'type' && key != 'parent') {
                            const fs = $('<fieldset>').appendTo($('#check-messages'));
                            $('<legend>', { 'text': key }).appendTo(fs);
                            for (text of value.split('\n')) {
                                $('<p>', { 'text': text }).appendTo(fs);
                            }
                        }
                    }
                    return;
                }
            }
        }
        $('<p>', { 'html': '&nbsp;' }).appendTo($('#check-messages'));
    }

    function open(tour_destinations = [], destinations = [], callback = console.log) {
        $('#check-messages').empty();
        let index = 0;
        for (const tour_dest of tour_destinations) {
            const dest_ref = tour_dest.ref;
            if (dest_ref) {
                let dest = destinations[dest_ref];
                const title = `${++index}. ${dest.label}`;
                const dest_var = tour_dest.var;
                if (dest && dest_var) {
                    dest = dest.variations[dest_var];
                }
                const messages = dest && dest.messages || [];
                $('<h2>', { 'text': title, 'css': { 'display': 'inline-block', 'margin-right': '10px' } }).appendTo($('#check-messages'));
                $('<button>', { 'text': 'edit' }).on('click', event => {
                    callback(tour_dest);
                }).appendTo($('#check-messages'));
                show_message_type('startMessage', messages);
                show_message_type('arriveMessage', messages);
                // showMessages('summary', messages);
            }
        }
        $('#cancel_check_messages').on('click', event => {
            close();
        });
        $('#message-check').show();
    }

    function close() {
        $('#message-check').hide();
        $('#cancel_check_messages').off('click');
    }

    return {
        'open': open,
        'close': close
    }
})();
