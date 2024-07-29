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
let MessageEditor = (function () {

    function add_message(template = [], message = {}) {
        let table = $('<table>', { 'class': 'message' }).appendTo($('#messages'));
        let thead = $('<thead>').appendTo(table);
        let tbody = $('<tbody>').appendTo(table);
        $('<tr>').append($('<th>', {
            'text': 'Key'
        }), $('<th>', {
            'text': 'Value'
        })).appendTo(thead);
        function add_row(key, type = 'text', list) {
            let input_field;
            if (type == 'textarea') {
                input_field = $('<textarea>').on('input', event => {
                    let e = $(event.target);
                    e.css('height', '5px');
                    e.css('height', e.prop('scrollHeight'));
                });
                setTimeout(() => input_field.css('height', input_field.prop('scrollHeight')));
            } else {
                input_field = $('<input>', { 'type': type });
                if (list) {
                    input_field.attr('list', list);
                }
            }
            input_field.attr('name', key);
            input_field.val(message[key] || '');
            $('<tr>').append($('<td>', {
                'text': key
            }), $('<td>').append(input_field)).appendTo(tbody);
        }
        template.forEach(args => {
            add_row(...args);
        });
        thead.find('th:last').hover(event => {
            title = $(event.target);
            $('#messages i').remove();
            parent_table = $(event.target).parents('table');
            let index = $('#messages table').index(parent_table);
            let length = $('#messages table').length;
            if (event.type == 'mouseenter') {
                title.css('position', 'relative');
                addIcon(title, 'fa-minus', 'Remove the message')
                    .on('click', ((index) => {
                        return (event) => {
                            $(event.target).parents('table').remove();
                        }
                    })(index));
                addIcon(title, 'fa-arrow-down', 'Down the message')
                    .addClass(index == length - 1 ? 'disabled-icon' : null)
                    .on('click', ((index) => {
                        return (event) => {
                            $('#messages table')[index + 1].after($('#messages table')[index])
                        }
                    })(index));
                addIcon(title, 'fa-arrow-up', 'Up the message')
                    .addClass(index == 0 ? 'disabled-icon' : null)
                    .on('click', ((index) => {
                        return (event) => {
                            $('#messages table')[index - 1].before($('#messages table')[index])
                        }
                    })(index));
            }
        });
    }

    function addIcon($element, className, title) {
        let count = $element.find("i").length
        let $icon = $('<i>')
            .addClass("fas")
            .addClass(className)
            .css('position', 'absolute')
            .css('right', (count * 20 + 5) + 'px')
            .css('top', '50%')
            .css('transform', 'translateY(-50%)')
            .css('cursor', 'pointer')
            .appendTo($element);
        return $icon.prop('title', title);

    }

    function get_messages() {
        let messages = [];
        $('#messages table').each((i, e) => {
            let message = {};
            $(e).find('input,textarea').each((i, e) => {
                let value = $(e).val();
                if (value != '') {
                    message[e.name] = value;
                }
            });
            if (Object.keys(message).length > 0) {
                messages.push(message);
            }
        });
        return messages;
    }

    function open(template = [], initial_messages = [], callback = console.log) {
        $('#messages').empty();
        $('#message-edit i').remove();
        addIcon($('#messages_title').css('position', 'relative'), 'fa-plus', 'Add a message')
            .on('click', (() => {
                return (event) => {
                    add_message(template);
                }
            })());
        initial_messages.forEach(message => {
            add_message(template, message);
        });
        $('#save_messages').on('click', event => {
            close();
            callback(get_messages());
        });
        $('#cancel_messages').on('click', event => {
            close();
        });
        $('#message-edit').show();
    }

    function close() {
        $('#message-edit').hide();
        $('#save_messages').off('click');
        $('#cancel_messages').off('click');
    }

    $(window).resize(function () {
        $('.message textarea').each((i, e) => {
            $(e).css('height', '5px');
            $(e).css('height', $(e).prop('scrollHeight'));
        });
    });

    return {
        'open': open,
        'close': close
    }
})();
