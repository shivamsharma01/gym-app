namespace AccessDemo2s
{
    partial class AutoMatrixForm
    {
        /// <summary>
        /// Required designer variable.
        /// </summary>
        private System.ComponentModel.IContainer components = null;

        /// <summary>
        /// Clean up any resources being used.
        /// </summary>
        /// <param name="disposing">true if managed resources should be disposed; otherwise, false.</param>
        protected override void Dispose(bool disposing)
        {
            if (disposing && (components != null))
            {
                components.Dispose();
            }
            base.Dispose(disposing);
        }

        #region Windows Form Designer generated code

        /// <summary>
        /// Required method for Designer support - do not modify
        /// the contents of this method with the code editor.
        /// </summary>
        private void InitializeComponent()
        {
            this.comboBox_RebootTime = new System.Windows.Forms.ComboBox();
            this.comboBox_RebootDay = new System.Windows.Forms.ComboBox();
            this.button_SetAutoMatrix = new System.Windows.Forms.Button();
            this.button_GetAutoMatrix = new System.Windows.Forms.Button();
            this.label2 = new System.Windows.Forms.Label();
            this.label1 = new System.Windows.Forms.Label();
            this.SuspendLayout();
            // 
            // comboBox_RebootTime
            // 
            this.comboBox_RebootTime.DropDownStyle = System.Windows.Forms.ComboBoxStyle.DropDownList;
            this.comboBox_RebootTime.FormattingEnabled = true;
            this.comboBox_RebootTime.Items.AddRange(new object[] {
            "0:00",
            "1:00",
            "2:00",
            "3:00",
            "4:00",
            "5:00",
            "6:00",
            "7:00",
            "8:00",
            "9:00",
            "10:00",
            "11:00",
            "12:00",
            "13:00",
            "14:00",
            "15:00",
            "16:00",
            "17:00",
            "18:00",
            "19:00",
            "20:00",
            "21:00",
            "22:00",
            "23:00"});
            this.comboBox_RebootTime.Location = new System.Drawing.Point(235, 71);
            this.comboBox_RebootTime.Name = "comboBox_RebootTime";
            this.comboBox_RebootTime.Size = new System.Drawing.Size(158, 22);
            this.comboBox_RebootTime.TabIndex = 11;
            // 
            // comboBox_RebootDay
            // 
            this.comboBox_RebootDay.DropDownStyle = System.Windows.Forms.ComboBoxStyle.DropDownList;
            this.comboBox_RebootDay.FormattingEnabled = true;
            this.comboBox_RebootDay.Items.AddRange(new object[] {
            "Never()",
            "Everyday()",
            "EverySunday()",
            "EveryMonday()",
            "EveryTuesday()",
            "EveryWednesday()",
            "EveryThursday()",
            "EveryFriday()",
            "EverySaturday()"});
            this.comboBox_RebootDay.Location = new System.Drawing.Point(235, 29);
            this.comboBox_RebootDay.Name = "comboBox_RebootDay";
            this.comboBox_RebootDay.Size = new System.Drawing.Size(158, 22);
            this.comboBox_RebootDay.TabIndex = 10;
            // 
            // button_SetAutoMatrix
            // 
            this.button_SetAutoMatrix.Location = new System.Drawing.Point(263, 135);
            this.button_SetAutoMatrix.Name = "button_SetAutoMatrix";
            this.button_SetAutoMatrix.Size = new System.Drawing.Size(85, 29);
            this.button_SetAutoMatrix.TabIndex = 9;
            this.button_SetAutoMatrix.Text = "Set()";
            this.button_SetAutoMatrix.UseVisualStyleBackColor = true;
            this.button_SetAutoMatrix.Click += new System.EventHandler(this.button_SetAutoMatrix_Click);
            // 
            // button_GetAutoMatrix
            // 
            this.button_GetAutoMatrix.Location = new System.Drawing.Point(86, 135);
            this.button_GetAutoMatrix.Name = "button_GetAutoMatrix";
            this.button_GetAutoMatrix.Size = new System.Drawing.Size(85, 29);
            this.button_GetAutoMatrix.TabIndex = 8;
            this.button_GetAutoMatrix.Text = "Get()";
            this.button_GetAutoMatrix.UseVisualStyleBackColor = true;
            this.button_GetAutoMatrix.Click += new System.EventHandler(this.button_GetAutoMatrix_Click);
            // 
            // label2
            // 
            this.label2.AutoSize = true;
            this.label2.Location = new System.Drawing.Point(19, 74);
            this.label2.Name = "label2";
            this.label2.Size = new System.Drawing.Size(210, 14);
            this.label2.TabIndex = 7;
            this.label2.Text = "AutoRebootTime():";
            // 
            // label1
            // 
            this.label1.AutoSize = true;
            this.label1.Location = new System.Drawing.Point(26, 32);
            this.label1.Name = "label1";
            this.label1.Size = new System.Drawing.Size(203, 14);
            this.label1.TabIndex = 6;
            this.label1.Text = "AutoRebootDay():";
            // 
            // AutoMatrixForm
            // 
            this.AutoScaleDimensions = new System.Drawing.SizeF(7F, 14F);
            this.AutoScaleMode = System.Windows.Forms.AutoScaleMode.Font;
            this.ClientSize = new System.Drawing.Size(420, 197);
            this.Controls.Add(this.comboBox_RebootTime);
            this.Controls.Add(this.comboBox_RebootDay);
            this.Controls.Add(this.button_SetAutoMatrix);
            this.Controls.Add(this.button_GetAutoMatrix);
            this.Controls.Add(this.label2);
            this.Controls.Add(this.label1);
            this.Font = new System.Drawing.Font("", 10.5F);
            this.FormBorderStyle = System.Windows.Forms.FormBorderStyle.FixedSingle;
            this.MaximizeBox = false;
            this.Name = "AutoMatrixForm";
            this.StartPosition = System.Windows.Forms.FormStartPosition.CenterParent;
            this.Text = "AutoMatrix()";
            this.Load += new System.EventHandler(this.AutoMatrixForm_Load);
            this.ResumeLayout(false);
            this.PerformLayout();

        }

        #endregion

        private System.Windows.Forms.ComboBox comboBox_RebootTime;
        private System.Windows.Forms.ComboBox comboBox_RebootDay;
        private System.Windows.Forms.Button button_SetAutoMatrix;
        private System.Windows.Forms.Button button_GetAutoMatrix;
        private System.Windows.Forms.Label label2;
        private System.Windows.Forms.Label label1;
    }
}