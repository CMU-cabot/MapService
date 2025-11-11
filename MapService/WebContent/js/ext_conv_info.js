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

$hulop.editor.ext_conv_info = function () {

    const init_css = `
        .editor-popup {
            display: none;
            z-index: 1;
            position: fixed;
            background-color: #0004;
            inset: 0;
        }

        .editor-form {
            position: fixed;
            display: flex;
            flex-direction: column;
            background-color: #FFF;
            font-size: 9pt;
            inset: 20px;
            padding: 10px;
            border: 1px solid;
            box-shadow: 5px 5px 5px;
        }

        #conversation-info fieldset {
            margin-bottom: 2em;
        }

        #conversation-info fieldset legend {
            font-size: 12pt;
            font-weight: bold;
        }
        
        #conversation-info {
            flex: 1;
            overflow: auto;
            margin-top: 10px;
        }

        .conv-input {
            margin: 8px 0px;
        }

        .conv-input label span {
            font-weight: bold;
            display: inline-block;
        }

        .conv-input textarea {
            width: 100%;
            box-sizing: border-box;
            resize: none;
            overflow: hidden;
        }

        .conv-input input[type="text"] {
            width: 25em;
        }

        .header-options {
            display: flex;
            align-items: center;
            justify-content: space-between;
        }

        .info-optional {
            display: none;
        }
    `;

    const init_html = `
        <div id="conversation-info-editor" class="editor-popup">
            <div class="editor-form">
                <div class="header-options">
                    <label><input type="checkbox" id="show-optional-info"> Show pavilion information</label>
                    <label>Go to <select id="goto-list"></select> </label>
                    <span>
                        <button id="edit-save">Save</button>
                        <button id="edit-close">Close</button>
                    </span>
                </div>
                <div id="conversation-info"></div>
            </div>
        </div>

        <dialog id="close_confirm_dialog">
            <form method="dialog">
                <p>
                    You have unsaved changes.<br>
                    Are you sure you want to close this page?<br>
                    <b>Any unsaved edits will be lost.</b>
                </p>
                <menu>
                    <button value="yes">Yes, I am sure</button>
                    <button value="no">No, Stay on page</button>
                </menu>
            </form>
        </dialog>
    `;

    const display_names = {
        'ext-string_category_ja': 'category',
        'ext-string_country_ja': 'country',
        'ext-string_organization_ja': 'organization',
        'ext-string_producer_name_ja': 'producer_name',
        'hulop_short_description_ja': 'description_ja',
        'hulop_short_description_en': 'description_en',
        'hulop_short_description_zh-CN': 'description_cn',
        'hulop_long_description_ja': 'detail_ja',
        'hulop_long_description_en': 'detail_en',
        'hulop_long_description_zh-CN': 'detail_cn',
        'ext-number_weekday_availability_sun_start': 'weekday_availability_sun_start',
        'ext-number_weekday_availability_sun_end': 'weekday_availability_sun_end',
        'ext-number_weekday_availability_mon_start': 'weekday_availability_mon_start',
        'ext-number_weekday_availability_mon_end': 'weekday_availability_mon_end',
        'ext-number_weekday_availability_tue_start': 'weekday_availability_tue_start',
        'ext-number_weekday_availability_tue_end': 'weekday_availability_tue_end',
        'ext-number_weekday_availability_wed_start': 'weekday_availability_wed_start',
        'ext-number_weekday_availability_wed_end': 'weekday_availability_wed_end',
        'ext-number_weekday_availability_thu_start': 'weekday_availability_thu_start',
        'ext-number_weekday_availability_thu_end': 'weekday_availability_thu_end',
        'ext-number_weekday_availability_fri_start': 'weekday_availability_fri_start',
        'ext-number_weekday_availability_fri_end': 'weekday_availability_fri_end',
        'ext-number_weekday_availability_sat_start': 'weekday_availability_sat_start',
        'ext-number_weekday_availability_sat_end': 'weekday_availability_sat_end',
    };

    function adjustAreaHeight(area) {
        area.css('height', '5px');
        area.css('height', `${area.prop('scrollHeight')}px`);
    }

    function resetScroll(index = 0) {
        const list = $('#conversation-info fieldset');
        if (list.length > index) {
            $('#goto-list').get(0).selectedIndex = index;
            list.get(index).scrollIntoView({ block: 'start' });
        }
    }

    function getFloorName(fl) {
        if (fl == 0) {
            return 'OUT';
        } else if (fl < 0) {
            return `B${-fl}F`;
        } else {
            return `${fl}F`;
        }
    }

    function init() {
        $('<style>').text(init_css).appendTo('head');
        $('body').append(init_html);
        $(window).resize(() => $('#conversation-info textarea').each((i, e) => adjustAreaHeight($(e))));
        $('#edit-save').on('click', event => {
            $('#save_button').is(':visible') && $('#save_button').click();
            $('#edit-save').prop('disabled', true);
        });
        $('#edit-close').on('click', event => {
            if ($('#restore_button').is(':visible')) {
                $('#close_confirm_dialog').get(0).showModal();
            } else {
                $('#conversation-info-editor').hide();
            }
        });
        $('#close_confirm_dialog').on('close', function () {
            if (this.returnValue === 'yes') {
                $('#restore_button').is(':visible') && $('#restore_button').click();
                $('#conversation-info-editor').hide();
            }
        });
        $('#show-optional-info').change(event => {
            const container = $('#conversation-info');
            const visibleTop = container.find('fieldset').filter(function () {
                return $(this).offset().top + $(this).outerHeight() > container.offset().top + 200;
            }).first();
            if (event.target.checked) {
                $('.info-optional').show();
            } else {
                $('.info-optional').hide();
            }
            setTimeout(() => {
                if (visibleTop.length > 0) {
                    visibleTop.get(0).scrollIntoView({ block: 'start' });
                } else {
                    resetScroll();
                }
            });
        });
        $('#goto-list').change(event => {
            const index = event.target.selectedIndex;
            if (index >= 0) {
                $('#conversation-info fieldset').get(index).scrollIntoView({ block: 'start' });
            }
        });
    }

    function getDestinations() {
        const destinations = $hulop.map.getRouteLayer().getSource().getFeatures().filter(feature => {
            if (feature.get('facil_id') && feature.get('name_ja') && feature.get('ent1_node') && feature.get('hulop_major_category') != '_nav_poi_') {
                if (feature.get('hulop_major_category') == undefined) {
                    console.log('hulop_major_category is undefined. ' + feature.get('name_ja'));
                    feature.set('hulop_major_category', '');
                }
                if (feature.get('hulop_tags') == undefined) {
                    console.log('hulop_tags is undefined. ' + feature.get('name_ja'));
                    feature.set('hulop_tags', '');
                }
                const tags = feature.get('hulop_tags').split(',').map(s => s.trim());
                return !tags.includes('hidden') && !tags.includes('demo');
            }
            return false;
        });
        destinations.sort((a, b) => {
            const floor_diff = a.get('ent1_fl') - b.get('ent1_fl');
            if (floor_diff != 0) {
                return floor_diff;
            }
            return a.get('name_ja').localeCompare(b.get('name_ja'));
        });
        return destinations;
    }

    function open_editor() {
        if ($('#save_button').is(':visible')) {
            if (!confirm('You have unsaved map edits. Do you want to save the changes before opening the conversation information?')) {
                return;
            }
            $('#save_button').click();
        }
        $('#conversation-info').empty();
        $('#goto-list').empty();
        const editing_id = $hulop.editor.editingFeature?.get('facil_id');
        let editing_index = 0;
        for (let dest of getDestinations()) {
            if (dest.get('facil_id') == editing_id) {
                editing_index = $('#goto-list option').length;
            }
            const parent = $('<fieldset>').appendTo($('#conversation-info'));
            const label_text = `${getFloorName(dest.get('ent1_fl'))} ${dest.get('name_ja')}`;
            $('<legend>', { 'text': label_text }).appendTo(parent);
            $('#goto-list').append($('<option>', {
                'text': label_text,
            }));

            function addField(key, required) {
                const div = $('<div class="conv-input">').appendTo(parent);
                required || div.addClass('info-optional');
                const name = display_names[key] || key;
                const label = $('<label>').appendTo(div);
                $('<span>', { 'text': name }).css('min-width', name.length < 20 ? '10em' : '18em').appendTo(label);
                let input_field;
                if (key.includes('_description_')) {
                    input_field = $('<textarea>').on('input', event => adjustAreaHeight($(event.target)));
                    setTimeout(() => adjustAreaHeight(input_field));
                } else if (key.startsWith('ext-number')) {
                    input_field = $('<input type="number">');
                } else {
                    input_field = $('<input type="text">');
                }
                input_field.val(dest.get(key)).appendTo(label)
                input_field.on('input', event => {
                    $('#edit-save').prop('disabled', false);
                    let val = $(event.target).val();
                    if (key.startsWith('ext-number')) {
                        if (val == '' || isNaN(val)) {
                            dest.unset(key);
                            return;
                        }
                        val = Number(val);
                    }
                    dest.set(key, val);
                });
            }

            addField('ext-string_category_ja', true);
            addField('ext-string_country_ja');
            addField('ext-string_organization_ja');
            addField('ext-string_producer_name_ja');
            addField('hulop_short_description_ja', true);
            addField('hulop_long_description_ja', true);
            addField('hulop_short_description_en', true);
            addField('hulop_long_description_en', true);
            addField('hulop_short_description_zh-CN', true);
            addField('hulop_long_description_zh-CN', true);
            addField('ext-number_weekday_availability_sun_start');
            addField('ext-number_weekday_availability_sun_end');
            addField('ext-number_weekday_availability_mon_start');
            addField('ext-number_weekday_availability_mon_end');
            addField('ext-number_weekday_availability_tue_start');
            addField('ext-number_weekday_availability_tue_end');
            addField('ext-number_weekday_availability_wed_start');
            addField('ext-number_weekday_availability_wed_end');
            addField('ext-number_weekday_availability_thu_start');
            addField('ext-number_weekday_availability_thu_end');
            addField('ext-number_weekday_availability_fri_start');
            addField('ext-number_weekday_availability_fri_end');
            addField('ext-number_weekday_availability_sat_start');
            addField('ext-number_weekday_availability_sat_end');
        }
        if ($('#show-optional-info').prop('checked')) {
            $('.info-optional').show();
        }
        $('#edit-save').prop('disabled', true);
        $('#conversation-info-editor').show();
        resetScroll(editing_index);
    }

    return { init, open_editor };
}();

$(document).ready(() => $hulop.editor.ext_conv_info.init());
