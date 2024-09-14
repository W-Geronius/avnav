/**
 * Created by andreas on 23.02.16.
 */

import React from "react";
import PropTypes from 'prop-types';
import keys from '../util/keys.jsx';
import Formatter from '../util/formatter.js';
import {WidgetFrame, WidgetProps} from "./WidgetBase";
import {SortableProps} from "../hoc/Sortable";

const ZoomWidget =(props)=>{
        let classes="zoomWidget ";
        if (props.className) classes+=" "+props.className;
        let val=props.default||'--';
        if (props.zoom !== undefined) {
            val=Formatter.formatDecimalOpt(props.zoom, 2, 1);
        }
        let rzoom=undefined;
        if (props.requiredZoom && props.requiredZoom != props.zoom){
            rzoom=Formatter.formatDecimalOpt(props.requiredZoom,2,1);
        }
        return (
        <WidgetFrame {...props} className={classes} unit={undefined}>
            <div className='widgetData'>{val}
                {
                    (rzoom !== undefined)?<div className="rzoom">({rzoom})</div>:''

                }
            </div>
        </WidgetFrame>
        );
    };

ZoomWidget.propTypes={
    ...WidgetProps,
    ...SortableProps,
    zoom: PropTypes.number,
    requiredZoom: PropTypes.number,
    default: PropTypes.number,
};

ZoomWidget.storeKeys={
    zoom: keys.map.currentZoom,
    requiredZoom: keys.map.requiredZoom,
    visible: keys.properties.showZoom
};
export default ZoomWidget;