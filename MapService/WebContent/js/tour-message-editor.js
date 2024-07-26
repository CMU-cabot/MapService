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
function MessageEditor(initial_messages = [], callback) {

    function add_message(message = {}) {
        let table = $('<table>', { 'class': 'message' }).appendTo($('#messages'));
        let thead = $('<thead>').appendTo(table);
        let tbody = $('<tbody>').appendTo(table);
        $('<tr>').append($('<th>', {
            'text': 'Key'
        }), $('<th>', {
            'text': 'Value'
        })).appendTo(thead);
        function add_row(key, type = 'text', list) {
            let input_field = $('<input>', {
                'type': type,
                'name': key,
                'value': message[key] || ''
            });
            if (list) {
                input_field.attr('list', list);
            }
            $('<tr>').append($('<td>', {
                'text': key
            }), $('<td>').append(input_field)).appendTo(tbody);
        }
        add_row('type', 'text', 'message_types');
        add_row('tags');
        add_row('age_group');
        add_row('timeFrom', 'time');
        add_row('timeUntil', 'time');
        add_row('dateFrom', 'date');
        add_row('dateUntil', 'date');
        add_row('text:en');
        add_row('text:ja');
        add_row('text:ja-pron');
        thead.find('th:last').hover(event => {
            title = $(event.target);
            $('#messages i').remove();
            parent_table = $(event.target).parents('table');
            let index = $('#messages table').index(parent_table);
            let length = $('#messages table').length;
            if (event.type == 'mouseenter') {
                title.css('position', 'relative');
                addIcon(title, 'fa-minus')
                    .prop('title', 'Remove the message')
                    .on('click', ((index) => {
                        return (event) => {
                            $(event.target).parents('table').remove();
                        }
                    })(index));
                addIcon(title, 'fa-arrow-down')
                    .addClass(index == length - 1 ? 'disabled-icon' : null)
                    .prop('title', 'Down the message')
                    .on('click', ((index) => {
                        return (event) => {
                            $('#messages table')[index + 1].after($('#messages table')[index])
                        }
                    })(index));
                addIcon(title, 'fa-arrow-up')
                    .addClass(index == 0 ? 'disabled-icon' : null)
                    .prop('title', 'Up the message')
                    .on('click', ((index) => {
                        return (event) => {
                            $('#messages table')[index - 1].before($('#messages table')[index])
                        }
                    })(index));
            }
        });
    }

    function addIcon($element, className) {
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
        return $icon;
    }

    function get_messages() {
        let messages = [];
        $('#messages table').each((i, e) => {
            let message = {};
            $(e).find('input').each((i, e) => {
                if (e.value != '') {
                    message[e.name] = e.value;
                }
            });
            if (Object.keys(message).length > 0) {
                messages.push(message);
            }
        });
        return messages;
    }

    $('#messages').empty();
    $('#message-edit i').remove();
    addIcon($('#messages_title').css('position', 'relative'), 'fa-plus')
        .prop('title', 'Add a message')
        .on('click', (() => {
            return (event) => {
                add_message();
            }
        })());
    initial_messages.forEach(message => {
        add_message(message);
    });
    $('#message-edit').show();
    $('#save_messages').on('click', event => {
        $('#message-edit').hide();
        callback(get_messages());
    });
    $('#cancel_messages').on('click', event => {
        $('#message-edit').hide();
    });
}
