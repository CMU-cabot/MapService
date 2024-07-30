onNodeClick = feature => {
    if (keyState.ctrlKey) {
        let node_id = feature && feature.get('node_id');
        let dest = node_id && lastData.destinations[node_id];
        if (!dest) {
            return true;
        }
        let td = $('.destination_selected td[key=ref]');
        if (td.length == 0) {
            return true;
        }
        applyChanges();
        tour['destinations'].push({
            'ref': node_id,
            '#ref': dest.label || ''
        });
        showTourProperty(tour);
        exportData();
        $('#tour_properties td[key=destinations] > table > tbody > tr:last td:first').trigger('click');
        return true;
    }
    if (keyState.altKey) {
